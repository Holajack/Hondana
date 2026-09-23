package hondana.ai

import java.util.Locale

/** System prompts and JSON schemas for the reading-assistant calls. */
object ClaudePrompts {

    fun languageName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return name.ifBlank { code }
    }

    private fun readingGuide(learningLanguage: String): String = when (learningLanguage.substringBefore('-')) {
        "ja" -> "the whole item written in hiragana"
        "zh" -> "pinyin with tone marks"
        "ko" -> "Revised Romanization of Korean"
        "ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg" -> "a Latin transliteration"
        "ar", "fa", "ur", "he", "yi" -> "a Latin transliteration"
        "hi", "mr", "ne", "bn", "th", "el", "ka", "hy" -> "a Latin transliteration"
        else -> "an empty string"
    }

    private fun orderGuide(layout: PageLayout): String = when (layout) {
        PageLayout.RIGHT_TO_LEFT ->
            "the page is read right to left: panels from the top right, and within a panel the bubbles on " +
                "the right come first"
        PageLayout.LEFT_TO_RIGHT ->
            "the page is read left to right: panels from the top left, and within a panel the bubbles on " +
                "the left come first"
        PageLayout.VERTICAL_STRIP ->
            "the image is part of a vertical strip read from top to bottom; bubbles side by side at the " +
                "same height are read left to right"
    }

    fun pageSystem(
        learningLanguage: String,
        nativeLanguage: String,
        layout: PageLayout,
        knownCharacters: List<PageCharacter>,
    ): String {
        val native = languageName(nativeLanguage)
        val learning = languageName(learningLanguage)
        val cast = if (knownCharacters.isEmpty()) {
            "No characters have been identified in this series yet."
        } else {
            "Characters already identified in this series. Reuse these exact names whenever the same " +
                "character speaks:\n" +
                knownCharacters.joinToString("\n") { c ->
                    if (c.description.isBlank()) "- ${c.name}" else "- ${c.name}: ${c.description}"
                }
        }
        return """
            You transcribe comics for someone learning $learning whose own language is $native.

            The user sends one screenshot from a comic reader. Find every piece of text in it: speech and thought bubbles, narration boxes, signs, and sound effects. Leave out any reader interface such as page numbers.

            For each item give:
            - text: the text exactly as written, with the lines of one bubble joined. Keep the original script. For Japanese, drop small furigana glosses printed beside the kanji.
            - kind: speech, thought, narration, sfx, sign, or other.
            - speaker: who is speaking. Use the character's name when it is known from the list below or from the story itself; otherwise give a short description that would identify the same person on another page, such as "tall man with glasses". Use "Narrator" for narration boxes and an empty string for sound effects and signs.
            - translation: a natural $native translation that keeps the speaker's tone.
            - reading: ${readingGuide(learningLanguage)}.
            - box: the item's bounding box in pixels of the image, as [left, top, right, bottom].

            Return the items in the order a reader meets them: ${orderGuide(layout)}.

            Under characters, list everyone who speaks in this image, with the name you used, a few words describing how they look so later pages can reuse the same names, and the kind of voice that suits them for text-to-speech.
        """.trimIndent() + "\n\n" + cast
    }

    fun pageUser(width: Int, height: Int): String =
        "This screenshot is $width x $height pixels. Transcribe it."

    fun translateSystem(learningLanguage: String, nativeLanguage: String): String {
        val native = languageName(nativeLanguage)
        val learning = languageName(learningLanguage)
        return "You translate comic dialogue from $learning into natural $native. " +
            "Translate each numbered line on its own, keeping the speaker's tone and register, and return " +
            "exactly one translation per line in the same order. Nearby lines are there for context."
    }

    fun explainSystem(learningLanguage: String, nativeLanguage: String): String {
        val native = languageName(nativeLanguage)
        val learning = languageName(learningLanguage)
        return """
            You are a patient $learning tutor. The learner's own language is $native, so write every explanation in $native.

            The learner is reading a comic and tapped one line they want to understand. Explain it so they can read it on their own next time:
            - reading: ${readingGuide(learningLanguage)} for the whole line.
            - translation: a natural translation.
            - literal: a word-for-word rendering that shows how the sentence is built.
            - words: each word or meaningful chunk in order, skipping punctuation, with its reading, dictionary form, part of speech, and its meaning in this line.
            - grammar: the grammar patterns, conjugations, particles or set phrases worth learning here, each with a short explanation.
            - notes: anything else that changes the meaning, such as politeness, slang, dialect, or wordplay. Keep it short, or leave it empty.
        """.trimIndent()
    }

    fun explainUser(line: String, context: List<String>): String {
        val others = context.filter { it.isNotBlank() && it != line }.take(12)
        return buildString {
            append("Line: ")
            append(line)
            if (others.isNotEmpty()) {
                append("\n\nOther text on the same page, for context:\n")
                others.forEach { append("- ").append(it).append('\n') }
            }
        }
    }

    /** Voice types Claude can suggest for a character; the app maps them to TTS voice and pitch. */
    val VOICE_TYPES = listOf(
        "female_child",
        "male_child",
        "female_young",
        "male_young",
        "female_adult",
        "male_adult",
        "female_elder",
        "male_elder",
        "narrator",
        "other",
    )

    private fun str(): Map<String, Any> = mapOf("type" to "string")

    private fun obj(properties: Map<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties,
        "required" to properties.keys.toList(),
        "additionalProperties" to false,
    )

    private fun arrayOf(items: Map<String, Any>): Map<String, Any> = mapOf("type" to "array", "items" to items)

    val pageSchema: Map<String, Any> = obj(
        linkedMapOf(
            "reading_direction" to mapOf(
                "type" to "string",
                "enum" to listOf("right_to_left", "left_to_right", "top_to_bottom"),
            ),
            "items" to arrayOf(
                obj(
                    linkedMapOf(
                        "text" to str(),
                        "kind" to mapOf(
                            "type" to "string",
                            "enum" to listOf("speech", "thought", "narration", "sfx", "sign", "other"),
                        ),
                        "speaker" to str(),
                        "translation" to str(),
                        "reading" to str(),
                        "box" to arrayOf(mapOf("type" to "integer")),
                    ),
                ),
            ),
            "characters" to arrayOf(
                obj(
                    linkedMapOf(
                        "name" to str(),
                        "description" to str(),
                        "voice_type" to mapOf("type" to "string", "enum" to VOICE_TYPES),
                    ),
                ),
            ),
        ),
    )

    val translationSchema: Map<String, Any> = obj(
        linkedMapOf("translations" to arrayOf(str())),
    )

    val explanationSchema: Map<String, Any> = obj(
        linkedMapOf(
            "reading" to str(),
            "translation" to str(),
            "literal" to str(),
            "words" to arrayOf(
                obj(
                    linkedMapOf(
                        "word" to str(),
                        "reading" to str(),
                        "dictionary_form" to str(),
                        "part_of_speech" to str(),
                        "meaning" to str(),
                    ),
                ),
            ),
            "grammar" to arrayOf(obj(linkedMapOf("pattern" to str(), "explanation" to str()))),
            "notes" to str(),
        ),
    )
}
