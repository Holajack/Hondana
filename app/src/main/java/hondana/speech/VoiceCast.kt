package hondana.speech

import hondana.core.CastEntry
import hondana.core.HondanaDatabase
import java.util.Locale

/**
 * Gives every character in a series their own voice. The first time someone
 * speaks they get a TTS voice (rotating through the installed ones) and a pitch
 * and pace from the voice type Claude suggested; the choice is saved per series
 * and can be edited from the reader.
 */
class VoiceCast(
    private val database: HondanaDatabase,
    private val speaker: Speaker,
) {

    suspend fun entries(mangaId: Long): List<CastEntry> = database.cast(mangaId)

    suspend fun save(mangaId: Long, entry: CastEntry) = database.saveCast(mangaId, entry)

    suspend fun remove(mangaId: Long, character: String) = database.deleteCast(mangaId, character)

    suspend fun voiceFor(
        mangaId: Long,
        character: String,
        voiceType: String,
        note: String,
        locale: Locale,
    ): CastEntry {
        val existing = database.cast(mangaId)
        existing.firstOrNull { it.character.equals(character, ignoreCase = true) }?.let { return it }

        val voices = speaker.voices(locale)
        val hash = stableHash(character)
        val voice = when {
            voices.isEmpty() -> null
            voiceType == "narrator" -> voices.first().name
            else -> voices[(existing.size + hash) % voices.size].name
        }
        val (pitch, rate) = baseFor(voiceType)
        // Two characters of the same type still sound a little different.
        val jitter = if (voiceType == "narrator") 0f else ((hash % 7) - 3) * 0.03f
        val entry = CastEntry(
            character = character,
            voice = voice,
            pitch = pitch + jitter,
            rate = rate,
            voiceType = voiceType,
            note = note,
        )
        database.saveCast(mangaId, entry)
        return entry
    }

    companion object {
        /** Starting pitch and pace for each voice type Claude can suggest. */
        fun baseFor(voiceType: String): Pair<Float, Float> = when (voiceType) {
            "female_child" -> 1.45f to 1.05f
            "male_child" -> 1.3f to 1.05f
            "female_young" -> 1.22f to 1.0f
            "male_young" -> 0.95f to 1.0f
            "female_adult" -> 1.1f to 0.97f
            "male_adult" -> 0.82f to 0.97f
            "female_elder" -> 1.0f to 0.88f
            "male_elder" -> 0.72f to 0.88f
            "narrator" -> 1.0f to 0.95f
            else -> 1.0f to 1.0f
        }

        fun stableHash(text: String): Int =
            text.lowercase().fold(7) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }
    }
}
