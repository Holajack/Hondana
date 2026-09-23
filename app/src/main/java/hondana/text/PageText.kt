package hondana.text

import kotlinx.serialization.Serializable

/**
 * One piece of text found on screen. Coordinates are fractions (0..1) of the
 * captured screen, so they map straight onto the reader view.
 */
@Serializable
data class TextBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** speech, thought, narration, sfx, sign or other. */
    val kind: String = "speech",
    /** Who says it; empty when unknown (always empty for on-device recognition). */
    val speaker: String = "",
    val translation: String = "",
    /** Reading aid: kana for Japanese, pinyin, romanization... */
    val reading: String = "",
) {
    val isSoundEffect: Boolean get() = kind == "sfx"
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

@Serializable
data class CharacterNote(
    val name: String,
    val description: String = "",
    val voiceType: String = "other",
)

/** Everything read from one screen, in reading order. */
@Serializable
data class PageText(
    val blocks: List<TextBlock>,
    /** "claude:<model>" or "on-device". */
    val engine: String,
    val characters: List<CharacterNote> = emptyList(),
) {
    val isFromClaude: Boolean get() = engine.startsWith("claude")
}
