package hondana.failover

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.MergedSource
import hondana.safety.AdultContentFilter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Finds a series on another installed source when its own source is down, and moves the library
 * entry there with everything that belongs to it.
 *
 * Only a strict match counts: nearly the same title ([TitleMatch]) and, when the chapter being
 * opened has a number, that same chapter on the new source. Sources are tried in this order: the
 * title's own source (sites move series to new addresses, which shows as "HTTP error 404"), the
 * user's migration sources (Browse → Migrate), sources already used in the library, pinned
 * sources, then any other source in the same language.
 */
class SourceFailover(
    private val sourceManager: SourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val migrateManga: MigrateMangaUseCase = Injekt.get(),
) {

    class Replacement(
        val manga: Manga,
        /** The chapter asked for, on the new source; null when none was asked for. */
        val chapter: Chapter?,
        val sourceName: String,
        /** The highest chapter number the new source has, if any is numbered. */
        val latestChapter: Double?,
    )

    /**
     * Looks for [manga] on the other installed sources. When [wanted] is given (the chapter being
     * opened), the replacement must have it, by number or by name when it has no number.
     * [includeOwnSource] also checks the title's own source for a new address.
     */
    suspend fun findReplacement(
        manga: Manga,
        wanted: Chapter?,
        includeOwnSource: Boolean = true,
    ): Replacement? = withIOContext {
        val titles = (listOf(manga.title) + TitleMatch.alternativeTitles(manga.description)).distinct()
        // The own source first: a series that only moved to a new address stays where it was.
        val own = (sourceManager.get(manga.source) as? CatalogueSource)?.takeIf { includeOwnSource && it !is MergedSource }
        val sources = listOfNotNull(own) + candidateSources(manga)
        logcat(LogPriority.INFO) { "Looking for ${manga.title} on ${sources.size} other sources" }

        // Search every candidate, a few at a time; keep whatever was found when time runs out.
        val found = ConcurrentLinkedQueue<Pair<Int, SManga>>()
        val permits = Semaphore(PARALLEL_SEARCHES)
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            coroutineScope {
                sources.forEachIndexed { index, source ->
                    val oldAddress = manga.url.takeIf { source.id == manga.source }
                    launch { permits.withPermit { search(source, titles, oldAddress)?.let { found += index to it } } }
                }
            }
        }

        // Earlier sources first; the first one that really has the chapter wins.
        found.sortedBy { it.first }.firstNotNullOfOrNull { (index, result) ->
            verify(sources[index], result, wanted)
        }
    }

    /**
     * Moves the library entry for [current] to [replacement]: read chapters, bookmarks, history,
     * categories, trackers, notes and custom cover come along. Downloads stay where they are.
     * A title that isn't in the library is left alone; it's simply read from the new source.
     * Returns whether the library entry moved.
     */
    suspend fun moveLibraryEntry(current: Manga, replacement: Manga): Boolean = withIOContext {
        if (!current.favorite) return@withIOContext false
        migrateManga(
            current = current,
            target = replacement,
            replace = true,
            presetFlags = setOf(
                MigrationFlag.CHAPTER,
                MigrationFlag.CATEGORY,
                MigrationFlag.TRACK,
                MigrationFlag.CUSTOM_COVER,
                MigrationFlag.NOTES,
                MigrationFlag.EXTRA,
            ),
        )
        // MigrateMangaUseCase swallows its errors; check the outcome.
        getManga.await(replacement.id)?.favorite == true
    }

    private suspend fun candidateSources(manga: Manga): List<CatalogueSource> {
        val lang = sourceManager.get(manga.source)?.lang
        val hidden = sourcePreferences.disabledSources().get()
        val usable = sourceManager.getVisibleOnlineSources()
            .filterIsInstance<CatalogueSource>()
            .filter { source ->
                source.id != manga.source &&
                    source.id != LocalSource.ID &&
                    source !is MergedSource &&
                    source.id.toString() !in hidden &&
                    !SourceHealth.isRecentlyDown(source.id) &&
                    !AdultContentFilter.blocksSource(source)
            }
        val byId = usable.associateBy { it.id }
        val chosen = sourcePreferences.migrationSources().get().mapNotNull { byId[it] }
        val inLibrary = runCatching { mangaRepository.getFavorites() }.getOrDefault(emptyList())
            .groupingBy { it.source }
            .eachCount()
        val pinned = sourcePreferences.pinnedSources().get()
        val sameLanguage = usable
            .filter { lang == null || it.lang == lang }
            .sortedWith(
                compareByDescending<CatalogueSource> { inLibrary[it.id] ?: 0 }
                    .thenByDescending { it.id.toString() in pinned }
                    .thenBy { it.name },
            )
        return (chosen + sameLanguage).distinctBy { it.id }.take(MAX_SOURCES)
    }

    /** The best-matching search result on [source] that isn't at [oldAddress], or null. */
    private suspend fun search(source: CatalogueSource, titles: List<String>, oldAddress: String?): SManga? {
        for (query in titles.take(2)) {
            val results = try {
                withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                    source.getSearchManga(1, query.sanitize(), source.getFilterList()).mangas
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "Search on ${source.name} failed: ${e.message}" }
                null
            } ?: return null // The source failed or is slow; don't press it with more queries.
            val best = results
                .filter { it.url != oldAddress && TitleMatch.isSameSeries(titles, it.title) }
                .filterNot { AdultContentFilter.blocksManga(source.id, source.name, it.title, it.getGenres()) }
                .maxByOrNull { TitleMatch.bestSimilarity(titles, it.title) }
            if (best != null) return best
        }
        return null
    }

    /** Loads [result]'s details and chapters and checks it has [wanted]; null when it doesn't. */
    /** The chapter a replacement should have: the next one to read, else the latest. */
    suspend fun chapterToCheck(manga: Manga): Chapter? = withIOContext {
        val numbered = getChaptersByMangaId.await(manga.id).filter { it.isRecognizedNumber }
        val lastRead = numbered.filter { it.read }.maxOfOrNull { it.chapterNumber }
        numbered.filter { !it.read && (lastRead == null || it.chapterNumber > lastRead) }.minByOrNull { it.chapterNumber }
            ?: numbered.maxByOrNull { it.chapterNumber }
    }

    private suspend fun verify(source: CatalogueSource, result: SManga, wanted: Chapter?): Replacement? {
        return try {
            val local = networkToLocalManga(result.toDomainManga(source.id))
            val refreshed = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS * 2) {
                updateMangaFromRemote(local, fetchDetails = true, fetchChapters = true).isSuccess
            }
            if (refreshed != true) return null
            val manga = getManga.await(local.id) ?: return null
            if (AdultContentFilter.blocksManga(manga, source.name)) return null
            val chapters = getChaptersByMangaId.await(manga.id).sortedBy { it.sourceOrder }
            if (chapters.isEmpty()) return null
            val chapter = wanted?.let { matchingChapter(chapters, it) ?: return null }
            val latest = chapters.filter { it.isRecognizedNumber }.maxOfOrNull { it.chapterNumber }
            Replacement(manga, chapter, source.name, latest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Couldn't check ${result.title} on ${source.name}: ${e.message}" }
            null
        }
    }

    private fun matchingChapter(chapters: List<Chapter>, wanted: Chapter): Chapter? {
        val same = if (wanted.isRecognizedNumber) {
            chapters.filter { it.isRecognizedNumber && abs(it.chapterNumber - wanted.chapterNumber) < 0.001 }
        } else {
            val name = TitleMatch.normalize(wanted.name)
            chapters.filter { TitleMatch.normalize(it.name) == name }
        }
        // Several groups may have uploaded it: prefer the same group, else the first upload.
        return same.firstOrNull { !wanted.scanlator.isNullOrBlank() && it.scanlator == wanted.scanlator }
            ?: same.lastOrNull()
    }

    private companion object {
        const val MAX_SOURCES = 20
        const val PARALLEL_SEARCHES = 4
        const val PER_SOURCE_TIMEOUT_MS = 20_000L
        const val SEARCH_TIMEOUT_MS = 45_000L
    }
}
