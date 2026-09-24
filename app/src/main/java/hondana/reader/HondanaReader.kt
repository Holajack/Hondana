package hondana.reader

import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import hondana.i18n.HMR
import hondana.safety.AdultContentFilter
import hondana.safety.AdultContentGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/** Everything Hondana adds to the reader, owned by [ReaderActivity]. */
class HondanaReader(val activity: ReaderActivity) {

    val autoScroll = AutoScrollController(activity)
    val assistant = ReaderAssistant(activity, autoScroll)
    val failover = ReaderFailover(activity)

    /** Which Hondana sheet is open over the reader, if any. */
    val sheet = MutableStateFlow(Sheet.NONE)

    enum class Sheet {
        NONE,
        VOICES,
        WORDS,
    }

    /** Set when the open title turned out to have sexual content; the reader closes. */
    private var blockedMangaId: Long? = null

    fun attach() {
        autoScroll.attach()
        failover.attach()
        assistant.messages
            .onEach { activity.toast(it) }
            .launchIn(activity.lifecycleScope)

        // Whichever screen opened the reader (library, updates, history, a link), never show a
        // title with sexual content. It is deleted once the reader has closed.
        activity.viewModel.state
            .mapNotNull { it.manga }
            .distinctUntilChanged()
            .onEach { manga ->
                if (blockedMangaId == null && AdultContentFilter.blocksManga(manga)) {
                    blockedMangaId = manga.id
                    AdultContentGuard.hold(manga.id)
                    activity.toast(HMR.strings.hondana_blocked_reader)
                    activity.finish()
                }
            }
            .launchIn(activity.lifecycleScope)
    }

    fun openSheet(which: Sheet) {
        activity.hideMenu()
        sheet.value = which
    }

    fun closeSheet() {
        sheet.value = Sheet.NONE
    }

    fun destroy() {
        assistant.destroy()
        blockedMangaId?.let(AdultContentGuard::release)
    }
}
