package hondana.core

import java.util.Locale

/** Languages the reading assistant offers, and what on-device text recognition can do with them. */
object Languages {

    enum class Script {
        LATIN,
        JAPANESE,
        CHINESE,
        KOREAN,
    }

    /** Languages offered as "the language I'm reading". */
    val learning = listOf(
        "ja", "zh", "ko", "en", "es", "fr", "de", "it", "pt", "nl", "pl", "tr", "vi", "id",
        "ru", "uk", "ar", "hi", "th", "el", "he", "fa",
    )

    /** Languages offered as "my language". */
    val native = listOf(
        "en", "es", "fr", "de", "it", "pt", "nl", "pl", "tr", "vi", "id", "ru", "uk", "ja", "zh", "ko",
        "ar", "hi", "th", "el", "he", "fa", "sv", "da", "no", "fi", "cs", "ro", "hu",
    )

    private val latinScript = setOf(
        "en", "es", "fr", "de", "it", "pt", "nl", "pl", "tr", "vi", "id", "sv", "da", "no", "fi", "cs",
        "ro", "hu", "ms", "tl", "ca", "hr", "sk", "sl", "et", "lv", "lt",
    )

    fun displayName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            .ifBlank { code }
    }

    /** Script for ML Kit text recognition, or null when on-device recognition can't read it. */
    fun ocrScript(code: String): Script? = when (code.substringBefore('-')) {
        "ja" -> Script.JAPANESE
        "zh" -> Script.CHINESE
        "ko" -> Script.KOREAN
        in latinScript -> Script.LATIN
        else -> null
    }

    fun locale(code: String): Locale = Locale.forLanguageTag(code)
}
