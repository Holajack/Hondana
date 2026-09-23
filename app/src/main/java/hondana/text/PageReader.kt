package hondana.text

import android.graphics.Bitmap
import hondana.ai.ClaudeService
import hondana.ai.PageAnalysisResult
import hondana.ai.PageCharacter
import hondana.ai.PageLayout
import hondana.core.CastEntry
import hondana.core.HondanaDatabase
import hondana.core.HondanaPreferences
import hondana.core.Languages
import hondana.core.TextEngine
import hondana.core.UserFacingException
import hondana.reader.averageHash
import hondana.reader.scaledToMax
import hondana.reader.toJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Reads the text on one captured screen, with Claude (text, speakers,
 * translations) or on-device ML Kit (text only), and caches Claude's answers so
 * a page is only paid for once.
 */
class PageReader(
    private val preferences: HondanaPreferences,
    private val claude: ClaudeService,
    private val ocr: OnDeviceOcr,
    private val database: HondanaDatabase,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val memory = object : LinkedHashMap<String, PageText>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageText>?): Boolean = size > 48
    }

    fun usesClaude(engine: TextEngine = preferences.textEngine().get()): Boolean = when (engine) {
        TextEngine.CLAUDE -> true
        TextEngine.ON_DEVICE -> false
        TextEngine.AUTO -> claude.isConfigured()
    }

    suspend fun read(
        screen: Bitmap,
        layout: PageLayout,
        cast: List<CastEntry>,
        engine: TextEngine = preferences.textEngine().get(),
    ): PageText {
        val learning = preferences.learningLanguage().get()
        val native = preferences.nativeLanguage().get()
        return if (usesClaude(engine)) {
            readWithClaude(screen, layout, cast, learning, native)
        } else {
            readOnDevice(screen, layout, learning)
        }
    }

    private suspend fun readOnDevice(screen: Bitmap, layout: PageLayout, learning: String): PageText {
        val script = Languages.ocrScript(learning) ?: throw UserFacingException(
            "On-device text recognition can't read ${Languages.displayName(learning)}. " +
                "Add a Claude API key in Settings → Reading assistant to read it with Claude.",
        )
        val blocks = ocr.read(screen, script, rightToLeft = layout == PageLayout.RIGHT_TO_LEFT)
        return PageText(blocks = blocks, engine = ENGINE_ON_DEVICE)
    }

    private suspend fun readWithClaude(
        screen: Bitmap,
        layout: PageLayout,
        cast: List<CastEntry>,
        learning: String,
        native: String,
    ): PageText {
        val model = preferences.claudeModel().get()
        val key = "claude|$model|$learning|$native|${layout.name}|${screen.averageHash()}"
        synchronized(memory) { memory[key] }?.let { return it }
        database.cachedPage(key)
            ?.let { cached -> runCatching { json.decodeFromString(PageText.serializer(), cached) }.getOrNull() }
            ?.let { page ->
                synchronized(memory) { memory[key] = page }
                return page
            }

        val image = withContext(Dispatchers.Default) {
            val scaled = screen.scaledToMax(CLAUDE_MAX_SIDE)
            Triple(scaled.toJpeg(85), scaled.width, scaled.height)
        }
        val (jpeg, width, height) = image
        val result = withContext(Dispatchers.IO) {
            claude.analyzePage(
                jpeg = jpeg,
                width = width,
                height = height,
                learningLanguage = learning,
                nativeLanguage = native,
                layout = layout,
                knownCharacters = cast.map { PageCharacter(it.character, it.note, it.voiceType) },
            )
        }
        val page = result.toPageText(width, height, "claude:$model")
        synchronized(memory) { memory[key] = page }
        database.cachePage(key, json.encodeToString(PageText.serializer(), page))
        return page
    }

    fun forget() = synchronized(memory) { memory.clear() }

    companion object {
        const val ENGINE_ON_DEVICE = "on-device"

        /** Claude scales larger images down to about this size anyway. */
        private const val CLAUDE_MAX_SIDE = 1568
    }
}

private fun PageAnalysisResult.toPageText(width: Int, height: Int, engine: String): PageText {
    val blocks = items.mapNotNull { item ->
        val text = item.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        val box = item.box
        val hasBox = box.size == 4 && box[2] > box[0] && box[3] > box[1]
        TextBlock(
            text = text,
            left = if (hasBox) (box[0] / width).toFloat().coerceIn(0f, 1f) else 0f,
            top = if (hasBox) (box[1] / height).toFloat().coerceIn(0f, 1f) else 0f,
            right = if (hasBox) (box[2] / width).toFloat().coerceIn(0f, 1f) else 0f,
            bottom = if (hasBox) (box[3] / height).toFloat().coerceIn(0f, 1f) else 0f,
            kind = item.kind,
            speaker = item.speaker.trim(),
            translation = item.translation.trim(),
            reading = item.reading.trim(),
        )
    }
    return PageText(
        blocks = blocks,
        engine = engine,
        characters = characters
            .filter { it.name.isNotBlank() }
            .map { CharacterNote(it.name.trim(), it.description.trim(), it.voiceType) },
    )
}
