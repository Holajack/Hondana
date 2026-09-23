package hondana.reader

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import hondana.ai.PageLayout
import hondana.core.CastEntry
import hondana.core.Hondana
import hondana.core.Languages
import hondana.core.ReadAloudText
import hondana.core.TextEngine
import hondana.core.VocabEntry
import hondana.speech.VoiceCast
import hondana.text.CharacterNote
import hondana.text.PageText
import hondana.text.TextBlock
import hondana.vocab.StudyActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The reading assistant in the reader:
 *
 * - **Lens**: freezes the screen, finds its text, and lets the reader tap any
 *   bubble to translate it, hear it, get a word-by-word explanation, look it up,
 *   save it, or send it to Anki.
 * - **Read aloud**: reads the live page bubble by bubble, each character in their
 *   own voice when Claude identified the speakers, then turns the page (or
 *   scrolls the strip) and carries on.
 */
class ReaderAssistant(
    private val activity: ReaderActivity,
    private val autoScroll: AutoScrollController,
) {

    private val preferences = Hondana.preferences
    private val scope get() = activity.lifecycleScope

    private val _lens = MutableStateFlow<LensState?>(null)
    val lens: StateFlow<LensState?> = _lens.asStateFlow()

    private val _readAloud = MutableStateFlow<ReadAloudState?>(null)
    val readAloud: StateFlow<ReadAloudState?> = _readAloud.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short notices for the reader, shown as toasts. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var lensJob: Job? = null
    private var detailJob: Job? = null
    private var speakJob: Job? = null
    private var readJob: Job? = null
    private val readPaused = MutableStateFlow(false)

    private val learning get() = preferences.learningLanguage().get()
    private val native get() = preferences.nativeLanguage().get()

    // Lens

    fun openLens() {
        if (_lens.value != null) return
        stopReadAloud()
        lensJob?.cancel()
        lensJob = scope.launch {
            activity.hideMenu()
            delay(MENU_ANIMATION_MS)
            val screen = try {
                ScreenCapture.capture(activity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
                return@launch
            }
            _lens.value = LensState(screenshot = screen, showTranslations = preferences.lensShowTranslations().get())
            loadLensPage(screen, preferences.textEngine().get())
        }
    }

    /** Reads the frozen screen again, e.g. with Claude after an on-device pass. */
    fun rescan(engine: TextEngine) {
        val screen = _lens.value?.screenshot ?: return
        lensJob?.cancel()
        stopSpeaking()
        lensJob = scope.launch { loadLensPage(screen, engine) }
    }

    private suspend fun loadLensPage(screen: Bitmap, engine: TextEngine) {
        _lens.update { it?.copy(loading = true, error = null, page = null, selected = null, detail = null) }
        try {
            val page = Hondana.pageReader.read(screen, currentLayout(), currentCast(), engine)
            _lens.update { it?.copy(loading = false, page = page) }
            rememberCharacters(page)
            if (_lens.value?.showTranslations == true) translateMissing()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lens.update { it?.copy(loading = false, error = e.userMessage()) }
        }
    }

    fun closeLens() {
        lensJob?.cancel()
        detailJob?.cancel()
        stopSpeaking()
        _lens.value = null
    }

    fun select(index: Int?) {
        _lens.update { state ->
            state?.copy(selected = index, detail = state.detail?.takeIf { it.index == index })
        }
    }

    fun toggleTranslations() {
        val show = !(_lens.value?.showTranslations ?: return)
        preferences.lensShowTranslations().set(show)
        _lens.update { it?.copy(showTranslations = show) }
        if (show) scope.launch { translateMissing() }
    }

    private suspend fun translateMissing() {
        val page = _lens.value?.page ?: return
        val missing = page.blocks.withIndex().filter { it.value.translation.isBlank() }
        if (missing.isEmpty()) return
        _lens.update { it?.copy(translating = true) }
        try {
            val translations = Hondana.translator.translate(missing.map { it.value.text }, learning, native)
            updateBlocks { blocks ->
                missing.forEachIndexed { i, (index, block) ->
                    blocks[index] = block.copy(translation = translations.getOrElse(i) { "" })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        } finally {
            _lens.update { it?.copy(translating = false) }
        }
    }

    fun translate(index: Int) {
        val block = lensBlock(index) ?: return
        if (block.translation.isNotBlank()) return
        scope.launch {
            _lens.update { it?.copy(translating = true) }
            try {
                val translation = Hondana.translator.translate(listOf(block.text), learning, native).first()
                updateBlocks { blocks -> blocks[index] = blocks[index].copy(translation = translation) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            } finally {
                _lens.update { it?.copy(translating = false) }
            }
        }
    }

    fun explain(index: Int) {
        val page = _lens.value?.page ?: return
        val block = page.blocks.getOrNull(index) ?: return
        if (!Hondana.claude.isConfigured()) {
            report("Explanations come from Claude. Add an API key in Settings → Reading assistant.")
            return
        }
        detailJob?.cancel()
        _lens.update { it?.copy(detail = BlockDetail(index, loading = true)) }
        detailJob = scope.launch {
            val detail = try {
                val result = withContext(Dispatchers.IO) {
                    Hondana.claude.explain(block.text, page.blocks.map { it.text }, learning, native)
                }
                BlockDetail(index, loading = false, explanation = result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BlockDetail(index, loading = false, error = e.userMessage())
            }
            _lens.update { state -> if (state?.detail?.index == index) state.copy(detail = detail) else state }
            val translation = detail.explanation?.translation.orEmpty()
            if (translation.isNotBlank() && block.translation.isBlank()) {
                updateBlocks { blocks -> blocks[index] = blocks[index].copy(translation = translation) }
            }
        }
    }

    fun speak(index: Int, translation: Boolean = false) {
        val page = _lens.value?.page ?: return
        val block = page.blocks.getOrNull(index) ?: return
        stopSpeaking()
        speakJob = scope.launch {
            _lens.update { it?.copy(speaking = index) }
            try {
                speakBlock(block, translation, page.characters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            } finally {
                _lens.update { state -> if (state?.speaking == index) state.copy(speaking = null) else state }
            }
        }
    }

    /** Reads every block of the frozen screen in order. */
    fun speakAll() {
        val page = _lens.value?.page ?: return
        stopSpeaking()
        speakJob = scope.launch {
            val useTranslation = preferences.readAloudText().get() == ReadAloudText.TRANSLATION
            try {
                for ((index, block) in page.blocks.withIndex()) {
                    if (block.isSoundEffect && !preferences.readSoundEffects().get()) continue
                    _lens.update { it?.copy(speaking = index, selected = index) }
                    speakBlock(block, useTranslation, page.characters)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            } finally {
                _lens.update { it?.copy(speaking = null) }
            }
        }
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        Hondana.speaker.stop()
    }

    fun copy(index: Int) {
        val block = lensBlock(index) ?: return
        StudyActions.copy(activity, block.text)
        report("Copied")
    }

    fun lookUp(text: String) = StudyActions.lookUp(activity, text, learning, native)

    fun saveLine(index: Int) {
        val block = lensBlock(index) ?: return
        save(
            VocabEntry(
                term = block.text,
                reading = block.reading,
                meaning = block.translation,
                sentence = block.text,
                sentenceTranslation = block.translation,
            ),
        )
    }

    fun saveWord(index: Int, word: hondana.ai.ExplainedWord) {
        val block = lensBlock(index) ?: return
        val term = word.dictionaryForm.ifBlank { word.word }
        save(
            VocabEntry(
                term = term,
                reading = word.reading,
                meaning = word.meaning,
                sentence = block.text,
                sentenceTranslation = block.translation,
            ),
        )
    }

    fun sendLineToAnki(index: Int) {
        val block = lensBlock(index) ?: return
        StudyActions.sendToAnki(
            activity,
            VocabEntry(
                term = block.text,
                reading = block.reading,
                meaning = block.translation,
                language = learning,
                source = sourceLabel(),
            ),
        )
    }

    private fun save(entry: VocabEntry) {
        scope.launch {
            Hondana.database.addVocab(
                entry.copy(language = learning, source = sourceLabel(), mangaId = activity.viewModel.manga?.id),
            )
            report("Saved to your words")
        }
    }

    private fun sourceLabel(): String {
        val state = activity.viewModel.state.value
        val title = state.manga?.title.orEmpty()
        val chapter = state.currentChapter?.chapter?.name.orEmpty()
        return listOf(title, chapter).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun lensBlock(index: Int): TextBlock? = _lens.value?.page?.blocks?.getOrNull(index)

    private fun updateBlocks(change: (MutableList<TextBlock>) -> Unit) {
        _lens.update { state ->
            val page = state?.page ?: return@update state
            val blocks = page.blocks.toMutableList()
            change(blocks)
            state.copy(page = page.copy(blocks = blocks))
        }
    }

    // Read aloud

    val isReadingAloud: Boolean get() = readJob?.isActive == true

    fun toggleReadAloud() = if (isReadingAloud) stopReadAloud() else startReadAloud()

    fun startReadAloud() {
        if (isReadingAloud) return
        closeLens()
        readPaused.value = false
        autoScroll.heldByReadAloud = true
        _readAloud.value = ReadAloudState()
        readJob = scope.launch {
            val window = activity.window
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                activity.hideMenu()
                delay(MENU_ANIMATION_MS)
                readAloudLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            } finally {
                autoScroll.heldByReadAloud = false
                _readAloud.value = null
                Hondana.speaker.stop()
                if (!activity.viewModel.readerPreferences.keepScreenOn().get() && !autoScroll.isOn) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    fun stopReadAloud() {
        readJob?.cancel()
        readJob = null
        Hondana.speaker.stop()
    }

    fun togglePauseReadAloud() {
        val pause = !readPaused.value
        readPaused.value = pause
        _readAloud.update { it?.copy(paused = pause) }
        if (pause) Hondana.speaker.stop()
    }

    /** Stops the current line; reading carries on with the next one. */
    fun skipLine() {
        if (!readPaused.value) Hondana.speaker.stop()
    }

    private suspend fun readAloudLoop() {
        val recent = ArrayDeque<String>()
        while (true) {
            _readAloud.update { it?.copy(preparing = true, block = null) }
            val screen = ScreenCapture.capture(activity)
            val marker = positionMarker()
            val page = Hondana.pageReader.read(screen, currentLayout(), currentCast())
            rememberCharacters(page)
            _readAloud.update { it?.copy(preparing = false, engine = page.engine) }

            val useTranslation = preferences.readAloudText().get() == ReadAloudText.TRANSLATION
            val strip = activity.viewModel.state.value.viewer is WebtoonViewer
            val autoAdvance = preferences.readAloudAutoAdvance().get()
            val lines = page.blocks.filter { block ->
                block.text.isNotBlank() &&
                    (preferences.readSoundEffects().get() || !block.isSoundEffect) &&
                    // On a strip, a bubble cut off by the bottom edge is read after the next scroll.
                    (!strip || !autoAdvance || block.bottom == 0f || block.bottom < 0.97f || !canScrollStrip())
            }

            var index = 0
            var moved = false
            while (index < lines.size) {
                readPaused.first { !it }
                if (positionMarker().movedFrom(marker)) {
                    moved = true
                    break
                }
                val block = lines[index]
                val key = normalize(block.text)
                if (key in recent) {
                    index++
                    continue
                }
                _readAloud.update { it?.copy(block = block) }
                speakBlock(block, useTranslation, page.characters)
                // Paused mid-line: say it again once resumed.
                if (readPaused.value) continue
                recent.addLast(key)
                while (recent.size > 80) recent.removeFirst()
                index++
            }
            if (moved) continue

            _readAloud.update { it?.copy(block = null) }
            if (!autoAdvance) {
                report("Finished this screen")
                return
            }
            if (!advance()) {
                report("Reached the end")
                return
            }
        }
    }

    private suspend fun speakBlock(block: TextBlock, useTranslation: Boolean, characters: List<CharacterNote>) {
        val text = if (useTranslation) {
            block.translation.ifBlank { Hondana.translator.translate(listOf(block.text), learning, native).first() }
        } else {
            block.text
        }
        val locale = Languages.locale(if (useTranslation) native else learning)
        val rate = preferences.speechRate().get() / 100f
        val mangaId = activity.viewModel.manga?.id
        val cast: CastEntry? = if (preferences.characterVoices().get() && mangaId != null && block.speaker.isNotBlank()) {
            val note = characters.firstOrNull { it.name.equals(block.speaker, ignoreCase = true) }
            val type = note?.voiceType ?: if (block.kind == "narration") "narrator" else "other"
            Hondana.voiceCast.voiceFor(mangaId, block.speaker, type, note?.description.orEmpty(), Languages.locale(learning))
        } else {
            null
        }
        val voiceName = when {
            cast == null -> null
            !useTranslation -> cast.voice
            // Cast voices speak the comic's language; pick a stable voice in the reader's language instead.
            else -> Hondana.speaker.voices(locale).takeIf { it.isNotEmpty() }
                ?.let { voices -> voices[VoiceCast.stableHash(cast.character) % voices.size].name }
        }
        Hondana.speaker.speak(text, locale, voiceName, cast?.pitch ?: 1f, (cast?.rate ?: 1f) * rate)
    }

    /** Moves to the next screen of content. Returns false at the end of what's loaded. */
    private suspend fun advance(): Boolean {
        return when (val viewer = activity.viewModel.state.value.viewer) {
            is PagerViewer -> {
                repeat(2) {
                    val before = viewer.currentPage
                    viewer.moveToNext()
                    val moved = withTimeoutOrNull(4_000) {
                        while (viewer.currentPage === before) delay(50)
                        true
                    } ?: false
                    if (!moved) return false
                    val now = viewer.currentPage
                    if (now is ChapterTransition) {
                        if (now is ChapterTransition.Next && now.to != null) {
                            // Give the next chapter a moment to load, then step past the transition page.
                            delay(1_500)
                            return@repeat
                        }
                        return false
                    }
                    awaitPageReady(now as? ReaderPage)
                    return true
                }
                false
            }
            is WebtoonViewer -> {
                val recycler = viewer.recycler
                if (!recycler.canScrollVertically(1)) return false
                recycler.smoothScrollBy(0, (recycler.height * 0.8f).toInt())
                delay(200)
                withTimeoutOrNull(4_000) {
                    while (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) delay(50)
                }
                awaitPageReady(viewer.currentPage as? ReaderPage)
                true
            }
            else -> false
        }
    }

    private suspend fun awaitPageReady(page: ReaderPage?) {
        if (page != null) {
            withTimeoutOrNull(20_000) {
                page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            }
        }
        // Let the image finish decoding and drawing.
        delay(450)
    }

    private fun canScrollStrip(): Boolean =
        (activity.viewModel.state.value.viewer as? WebtoonViewer)?.recycler?.canScrollVertically(1) == true

    private data class Marker(val item: Any?, val offset: Int) {
        fun movedFrom(other: Marker): Boolean = item !== other.item || abs(offset - other.offset) > 24
    }

    private fun positionMarker(): Marker = when (val viewer = activity.viewModel.state.value.viewer) {
        is PagerViewer -> Marker(viewer.currentPage, 0)
        is WebtoonViewer -> Marker(viewer, viewer.recycler.computeVerticalScrollOffset())
        else -> Marker(viewer, 0)
    }

    // Shared

    private fun currentLayout(): PageLayout {
        val viewer = activity.viewModel.state.value.viewer
        val japanese = learning.startsWith("ja")
        return when {
            viewer is R2LPagerViewer -> PageLayout.RIGHT_TO_LEFT
            viewer is WebtoonViewer && !japanese -> PageLayout.VERTICAL_STRIP
            japanese -> PageLayout.RIGHT_TO_LEFT
            else -> PageLayout.LEFT_TO_RIGHT
        }
    }

    private suspend fun currentCast(): List<CastEntry> {
        val mangaId = activity.viewModel.manga?.id ?: return emptyList()
        return Hondana.voiceCast.entries(mangaId)
    }

    /** Stores characters Claude identified, so later pages reuse their names and voices. */
    private suspend fun rememberCharacters(page: PageText) {
        if (!page.isFromClaude) return
        val mangaId = activity.viewModel.manga?.id ?: return
        val locale = Languages.locale(learning)
        for (character in page.characters) {
            Hondana.voiceCast.voiceFor(mangaId, character.name, character.voiceType, character.description, locale)
        }
    }

    private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

    private fun report(e: Exception) = report(e.userMessage())

    private fun report(message: String) {
        _messages.tryEmit(message)
    }

    private fun Exception.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    fun destroy() {
        stopReadAloud()
        closeLens()
    }

    companion object {
        /** How long the reader menu takes to slide away before the screen is captured. */
        private const val MENU_ANIMATION_MS = 300L
    }
}
