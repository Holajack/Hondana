package hondana.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What Claude returns for one screen of a comic. Mirrors [ClaudePrompts.pageSchema]. */
@Serializable
data class PageAnalysisResult(
    @SerialName("reading_direction") val readingDirection: String = "",
    val items: List<PageItem> = emptyList(),
    val characters: List<PageCharacter> = emptyList(),
)

@Serializable
data class PageItem(
    val text: String = "",
    val kind: String = "speech",
    val speaker: String = "",
    val translation: String = "",
    val reading: String = "",
    /** [left, top, right, bottom] in pixels of the image that was sent. */
    val box: List<Double> = emptyList(),
)

@Serializable
data class PageCharacter(
    val name: String = "",
    val description: String = "",
    @SerialName("voice_type") val voiceType: String = "other",
)

@Serializable
data class TranslationResult(
    val translations: List<String> = emptyList(),
)

/** A word-by-word explanation of one line. Mirrors [ClaudePrompts.explanationSchema]. */
@Serializable
data class ExplanationResult(
    val reading: String = "",
    val translation: String = "",
    val literal: String = "",
    val words: List<ExplainedWord> = emptyList(),
    val grammar: List<GrammarPoint> = emptyList(),
    val notes: String = "",
)

@Serializable
data class ExplainedWord(
    val word: String = "",
    val reading: String = "",
    @SerialName("dictionary_form") val dictionaryForm: String = "",
    @SerialName("part_of_speech") val partOfSpeech: String = "",
    val meaning: String = "",
)

@Serializable
data class GrammarPoint(
    val pattern: String = "",
    val explanation: String = "",
)

/** How the page is laid out, so Claude can put the text in reading order. */
enum class PageLayout {
    /** Japanese-style pages, read right to left. */
    RIGHT_TO_LEFT,

    /** Western comics and most manhua pages. */
    LEFT_TO_RIGHT,

    /** Webtoons and other long vertical strips. */
    VERTICAL_STRIP,
}

/** A user-facing failure from the Claude API. [message] is safe to show as-is. */
class ClaudeException(message: String, cause: Throwable? = null) : Exception(message, cause)
