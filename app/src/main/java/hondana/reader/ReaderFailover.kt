package hondana.reader

import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.system.toast
import hondana.core.Hondana
import hondana.failover.ChapterLoadErrors
import hondana.failover.SourceFailover
import hondana.failover.SourceHealth
import hondana.i18n.HMR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.injectLazy

/**
 * Keeps reading going when a source's site is down. When a chapter won't load and the phone is
 * online, [SourceFailover] looks for the series on the other sources; the library entry moves
 * there with the reading progress, and the reader reopens on the same chapter.
 *
 * - The chapter the reader opens with fails, or opens but none of its pages load: the switch
 *   happens as soon as a match is found, or a card says the series wasn't found.
 * - The next chapter fails to preload: the search runs in the background, and the switch is
 *   offered at the end of the current chapter, or made when the user goes on to that chapter.
 */
class ReaderFailover(private val activity: ReaderActivity) {

    sealed interface Status {
        data object Idle : Status

        /** Looking for the series elsewhere while the user waits. */
        data class Searching(val sourceName: String, val title: String, val closeReader: Boolean) : Status

        /** Moving the library entry and reopening the reader. */
        data class Moving(val targetName: String) : Status

        /** The next chapter can be read from [replacement]. */
        data class Ready(val sourceName: String, val replacement: SourceFailover.Replacement) : Status

        data class NotFound(val sourceName: String, val title: String, val closeReader: Boolean) : Status
    }

    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val engine by lazy { SourceFailover() }
    private val sourceManager: SourceManager by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val downloadManager: DownloadManager by injectLazy()

    private var job: Job? = null
    private var searchedChapterId: Long? = null
    private var switchWhenFound = false
    private var closeIfNotFound = false

    fun attach() {
        ChapterLoadErrors.failures
            .onEach(::onChapterFailure)
            .launchIn(activity.lifecycleScope)

        // A chapter whose page list loaded but whose images all fail is just as unreadable.
        activity.viewModel.state
            .map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .flatMapLatest { chapter -> chapter?.let(::unreadable) ?: flowOf(null) }
            .filterNotNull()
            .onEach { (chapter, error) -> onPagesFailed(chapter, error) }
            .launchIn(activity.lifecycleScope)
    }

    /** Emits the chapter once at least two of its pages failed and none loaded. Downloads don't count. */
    private fun unreadable(chapter: ReaderChapter) = chapter.stateFlow.flatMapLatest { state ->
        val pages = (state as? ReaderChapter.State.Loaded)?.pages.orEmpty()
        if (pages.size < 2 || chapter.pageLoader?.isLocal != false) {
            flowOf(null)
        } else {
            combine(pages.map { it.statusFlow }) { statuses ->
                val errors = statuses.filterIsInstance<Page.State.Error>()
                if (errors.size >= 2 && statuses.none { it == Page.State.Ready }) chapter to errors.first().error else null
            }
        }
    }.distinctUntilChanged { old, new -> (old == null) == (new == null) }

    /** The chapter the reader opened with couldn't load. True when this takes over from the error. */
    fun onOpeningFailed(chapterId: Long, error: Throwable): Boolean {
        val manga = activity.viewModel.state.value.manga ?: return false
        if (!canTakeOver(manga, error)) return false
        search(manga, chapterId, error, userIsWaiting = true, closeReader = true)
        return true
    }

    private fun onPagesFailed(chapter: ReaderChapter, error: Throwable) {
        val manga = activity.viewModel.state.value.manga ?: return
        val chapterId = chapter.chapter.id ?: return
        if (searchedChapterId == chapterId || !canTakeOver(manga, error)) return
        search(manga, chapterId, error, userIsWaiting = true, closeReader = false)
    }

    private fun onChapterFailure(failure: ChapterLoadErrors.Failure) {
        val state = activity.viewModel.state.value
        val manga = state.manga ?: return
        // Preloads also run for the previous chapter; only the next one matters here.
        if (!failure.opening && state.viewerChapters?.nextChapter?.chapter?.id != failure.chapterId) return
        if (!canTakeOver(manga, failure.error)) return
        val current = mutableStatus.value
        when {
            // The user went on to the chapter found elsewhere: go now. Preloads (which repeat on
            // the last pages of a chapter) only start the search.
            current is Status.Ready && searchedChapterId == failure.chapterId -> if (failure.opening) {
                switchTo(manga, current.replacement)
            }
            current is Status.Searching || current is Status.Moving || current is Status.NotFound -> Unit
            searchedChapterId == failure.chapterId && job?.isActive == true -> if (failure.opening) {
                switchWhenFound = true
                mutableStatus.value = Status.Searching(sourceName(manga), manga.title, closeReader = false)
            }
            // Already looked and found nothing.
            searchedChapterId == failure.chapterId -> if (failure.opening) {
                mutableStatus.value = Status.NotFound(sourceName(manga), manga.title, closeReader = false)
            }
            else -> search(manga, failure.chapterId, failure.error, userIsWaiting = failure.opening, closeReader = false)
        }
    }

    private fun canTakeOver(manga: Manga, error: Throwable): Boolean {
        if (!Hondana.preferences.switchSourcesWhenDown().get()) return false
        val source = sourceManager.get(manga.source)
        if (source !is HttpSource || source is MergedSource) return false
        return SourceHealth.isOnline(activity) && SourceHealth.isSiteFailure(error)
    }

    private fun sourceName(manga: Manga) = sourceManager.getOrStub(manga.source).name

    private fun search(manga: Manga, chapterId: Long, error: Throwable, userIsWaiting: Boolean, closeReader: Boolean) {
        job?.cancel()
        searchedChapterId = chapterId
        switchWhenFound = userIsWaiting
        closeIfNotFound = closeReader
        val sourceName = sourceName(manga)
        mutableStatus.value = if (userIsWaiting) Status.Searching(sourceName, manga.title, closeReader) else Status.Idle

        job = activity.lifecycleScope.launch {
            val wanted = withIOContext { getChapter.await(chapterId) }
            if (wanted == null || withIOContext { isDownloaded(manga, wanted) }) {
                // A download that won't open is a problem on the phone, not the site.
                mutableStatus.value = Status.Idle
                if (closeIfNotFound) {
                    activity.toast(error.message)
                    activity.finish()
                }
                return@launch
            }
            SourceHealth.markDown(manga.source)
            logcat(LogPriority.INFO) { "$sourceName isn't working; looking for ${manga.title} elsewhere" }
            // Whatever goes wrong while searching, the reader must not crash over it.
            val replacement = try {
                engine.findReplacement(manga, wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Looking for ${manga.title} elsewhere failed" }
                null
            }
            when {
                replacement != null && switchWhenFound -> switchTo(manga, replacement)
                replacement != null -> mutableStatus.value = Status.Ready(sourceName, replacement)
                switchWhenFound -> mutableStatus.value = Status.NotFound(sourceName, manga.title, closeIfNotFound)
                else -> mutableStatus.value = Status.Idle
            }
        }
    }

    private fun isDownloaded(manga: Manga, chapter: Chapter) = downloadManager.isChapterDownloaded(
        chapter.name,
        chapter.scanlator,
        chapter.url,
        manga.ogTitle,
        manga.source,
    )

    /** Moves the library entry to the replacement and reopens the reader there. */
    fun switchTo(manga: Manga, replacement: SourceFailover.Replacement) {
        val sourceName = sourceName(manga)
        mutableStatus.value = Status.Moving(replacement.sourceName)
        activity.lifecycleScope.launch {
            val moved = try {
                engine.moveLibraryEntry(manga, replacement.manga)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Moving ${manga.title} failed" }
                false
            }
            val message = if (moved && replacement.manga.source == manga.source) {
                activity.stringResource(HMR.strings.hondana_failover_new_address, manga.title, sourceName)
            } else if (moved) {
                activity.stringResource(HMR.strings.hondana_failover_moved, sourceName, manga.title, replacement.sourceName)
            } else {
                activity.stringResource(HMR.strings.hondana_failover_reading_from, sourceName, replacement.sourceName)
            }
            activity.toast(message, Toast.LENGTH_LONG)
            activity.finish()
            activity.startActivity(ReaderActivity.newIntent(activity, replacement.manga.id, replacement.chapter?.id))
        }
    }

    /** The "Continue on …" button of the Ready card. */
    fun continueOnReplacement() {
        val manga = activity.viewModel.state.value.manga ?: return
        (mutableStatus.value as? Status.Ready)?.let { switchTo(manga, it.replacement) }
    }

    /** Opens Browse → Global search for the title, to pick a source by hand. */
    fun searchByHand(title: String) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
                action = MainActivity.INTENT_SEARCH
                putExtra(MainActivity.INTENT_SEARCH_QUERY, title)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        activity.finish()
    }

    /** The card was dismissed. A reader with nothing to show closes too. */
    fun dismiss() {
        val closeReader = when (val current = mutableStatus.value) {
            is Status.Searching -> current.closeReader
            is Status.NotFound -> current.closeReader
            else -> false
        }
        job?.cancel()
        mutableStatus.value = Status.Idle
        if (closeReader) activity.finish()
    }
}
