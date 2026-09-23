package hondana.reader

import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Everything Hondana adds to the reader, owned by [ReaderActivity]. */
class HondanaReader(val activity: ReaderActivity) {

    val autoScroll = AutoScrollController(activity)
    val assistant = ReaderAssistant(activity, autoScroll)

    /** Which Hondana sheet is open over the reader, if any. */
    val sheet = MutableStateFlow(Sheet.NONE)

    enum class Sheet {
        NONE,
        VOICES,
        WORDS,
    }

    fun attach() {
        autoScroll.attach()
        assistant.messages
            .onEach { activity.toast(it) }
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
    }
}
