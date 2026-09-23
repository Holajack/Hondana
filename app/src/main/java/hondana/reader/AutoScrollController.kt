package hondana.reader

import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import hondana.core.Hondana
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import kotlin.math.roundToInt

/**
 * Auto-scroll for every reading mode, replacing TachiyomiSY's timer loop:
 *
 * - Webtoon / long strip: smooth, frame-by-frame scrolling at an adjustable speed
 *   (or a screen at a time, when "smooth auto scroll" is off).
 * - Paged modes: turns the page every N seconds; the countdown restarts whenever
 *   the page changes, including when the reader turns it by hand.
 *
 * It pauses while the menu or a dialog is open, while the reader touches the
 * page, and while read-aloud is turning pages itself. The on/off state is
 * TachiyomiSY's `ReaderViewModel.State.autoScroll`, so the EH utilities panel
 * drives the same engine.
 */
class AutoScrollController(private val activity: ReaderActivity) {

    private val preferences = Hondana.preferences
    private val readerPreferences = activity.viewModel.readerPreferences

    private val _paused = MutableStateFlow(false)

    /** Paused from the auto-scroll controls; auto-scroll itself stays on. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Set while read-aloud is turning the pages itself. */
    @Volatile
    var heldByReadAloud = false

    private var pageTimerStart = 0L
    private var pagerIdle = true

    private val pageListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            pageTimerStart = SystemClock.uptimeMillis()
        }

        override fun onPageScrollStateChanged(state: Int) {
            pagerIdle = state == ViewPager.SCROLL_STATE_IDLE
        }
    }

    val isOn: Boolean get() = activity.viewModel.state.value.autoScroll

    fun attach() {
        migrateDefaultInterval()
        activity.lifecycleScope.launch {
            activity.viewModel.state
                .map { it.autoScroll }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _paused.value = false
                    if (enabled) {
                        activity.repeatOnLifecycle(Lifecycle.State.STARTED) { run() }
                    }
                }
        }
    }

    fun toggle() = set(!isOn)

    fun set(on: Boolean) {
        activity.viewModel.toggleAutoScroll(on)
        if (on) activity.hideMenu()
    }

    fun togglePause() {
        _paused.value = !_paused.value
    }

    /** Seconds per page in paged modes and screen-by-screen webtoon scrolling. */
    fun pageSeconds(): Int {
        val value = readerPreferences.autoscrollInterval().get()
        return if (value > 0f) value.roundToInt().coerceAtLeast(1) else DEFAULT_PAGE_SECONDS
    }

    fun speedPreference(): Preference<Int> = preferences.autoScrollSpeed()

    fun intervalPreference(): Preference<Float> = readerPreferences.autoscrollInterval()

    /** Whether the current viewer scrolls continuously (so speed, not seconds per page, applies). */
    fun usesContinuousScroll(): Boolean =
        activity.viewModel.state.value.viewer is WebtoonViewer && readerPreferences.smoothAutoScroll().get()

    fun faster() {
        if (usesContinuousScroll()) {
            val pref = preferences.autoScrollSpeed()
            pref.set((pref.get() + 1).coerceAtMost(MAX_SPEED))
        } else {
            setPageSeconds(pageSeconds() - 1)
        }
    }

    fun slower() {
        if (usesContinuousScroll()) {
            val pref = preferences.autoScrollSpeed()
            pref.set((pref.get() - 1).coerceAtLeast(1))
        } else {
            setPageSeconds(pageSeconds() + 1)
        }
    }

    private fun setPageSeconds(seconds: Int) {
        // Goes through the view model so the EH utilities panel shows the same value.
        activity.viewModel.setAutoScrollFrequency(seconds.coerceIn(1, 120).toFloat().toString())
    }

    /** TachiyomiSY defaults to 3 seconds a page, which is too fast to read a manga page. */
    private fun migrateDefaultInterval() {
        val migrated = preferences.autoScrollIntervalMigrated()
        if (migrated.get()) return
        migrated.set(true)
        if (readerPreferences.autoscrollInterval().get() == 3f) {
            setPageSeconds(DEFAULT_PAGE_SECONDS)
        }
    }

    private suspend fun run() {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var trackedPager: Pager? = null
        var lastFrame = 0L
        var carry = 0f
        pageTimerStart = SystemClock.uptimeMillis()
        try {
            while (true) {
                val frame = awaitFrame()
                val seconds = if (lastFrame == 0L) 0f else ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.1f)
                lastFrame = frame
                val now = SystemClock.uptimeMillis()
                val state = activity.viewModel.state.value
                val viewer = state.viewer

                val pager = (viewer as? PagerViewer)?.pager
                if (pager !== trackedPager) {
                    trackedPager?.removeOnPageChangeListener(pageListener)
                    pager?.addOnPageChangeListener(pageListener)
                    trackedPager = pager
                    pagerIdle = true
                    pageTimerStart = now
                }

                val blocked = state.menuVisible ||
                    state.dialog != null ||
                    _paused.value ||
                    heldByReadAloud ||
                    !activity.hasWindowFocus()
                if (blocked) {
                    carry = 0f
                    pageTimerStart = now
                    continue
                }

                when (viewer) {
                    is WebtoonViewer -> {
                        val recycler = viewer.recycler
                        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                            // The reader is dragging or flinging the strip.
                            if (!preferences.autoScrollResumeAfterTouch().get()) _paused.value = true
                            carry = 0f
                            pageTimerStart = now
                            continue
                        }
                        if (readerPreferences.smoothAutoScroll().get()) {
                            if (!recycler.canScrollVertically(1)) {
                                // End of the strip and no next chapter loaded below it.
                                set(false)
                                activity.showMenu()
                                return
                            }
                            val density = activity.resources.displayMetrics.density
                            carry += preferences.autoScrollSpeed().get() * DP_PER_SPEED_STEP * density * seconds
                            val dy = carry.toInt()
                            if (dy > 0) {
                                recycler.scrollBy(0, dy)
                                carry -= dy
                            }
                        } else if (now - pageTimerStart >= pageSeconds() * 1000L) {
                            viewer.scrollDown()
                            pageTimerStart = now
                        }
                    }
                    is PagerViewer -> {
                        if (!pagerIdle) {
                            pageTimerStart = now
                            continue
                        }
                        if (now - pageTimerStart >= pageSeconds() * 1000L) {
                            viewer.moveToNext()
                            pageTimerStart = now
                        }
                    }
                    else -> pageTimerStart = now
                }
            }
        } finally {
            trackedPager?.removeOnPageChangeListener(pageListener)
            if (!readerPreferences.keepScreenOn().get()) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    companion object {
        const val MAX_SPEED = 30
        const val DEFAULT_PAGE_SECONDS = 10
        private const val DP_PER_SPEED_STEP = 10f
    }
}
