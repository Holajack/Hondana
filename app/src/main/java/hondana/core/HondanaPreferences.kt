package hondana.core

import hondana.ai.ClaudeModels
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class HondanaPreferences(private val store: PreferenceStore) {

    // Auto-scroll

    /** Continuous webtoon speed, 1..30. Each step is 10 dp per second. */
    fun autoScrollSpeed() = store.getInt("hondana_autoscroll_speed", 6)

    /** Keep scrolling after the user drags or flings the page (otherwise pause). */
    fun autoScrollResumeAfterTouch() = store.getBoolean("hondana_autoscroll_resume_after_touch", true)

    /** One-time move off TachiyomiSY's 3-second default page interval. */
    fun autoScrollIntervalMigrated() =
        store.getBoolean(Preference.appStateKey("hondana_autoscroll_interval_migrated"), false)

    // Languages

    /** Language of the comics being read (BCP 47, e.g. "ja"). */
    fun learningLanguage() = store.getString("hondana_learning_language", "ja")

    /** The reader's own language, used for translations and explanations. */
    fun nativeLanguage() = store.getString("hondana_native_language", "en")

    // Engines

    fun textEngine() = store.getEnum("hondana_text_engine", TextEngine.AUTO)

    fun translationEngine() = store.getEnum("hondana_translation_engine", TranslationEngine.AUTO)

    // Claude

    /** Private key: never written to backups. */
    fun claudeApiKey() = store.getString(Preference.privateKey("hondana_claude_api_key"), "")

    fun claudeModel() = store.getString("hondana_claude_model", ClaudeModels.DEFAULT)

    fun claudeEffort() = store.getEnum("hondana_claude_effort", ClaudeEffort.MEDIUM)

    // Read aloud

    fun readAloudText() = store.getEnum("hondana_read_aloud_text", ReadAloudText.ORIGINAL)

    /** Speech rate in percent of normal, 50..200. */
    fun speechRate() = store.getInt("hondana_speech_rate", 100)

    fun characterVoices() = store.getBoolean("hondana_character_voices", true)

    /** Turn the page (or scroll the strip) and keep reading when a screen is done. */
    fun readAloudAutoAdvance() = store.getBoolean("hondana_read_aloud_auto_advance", true)

    fun readSoundEffects() = store.getBoolean("hondana_read_sound_effects", false)

    // Lens

    fun lensShowTranslations() = store.getBoolean("hondana_lens_show_translations", false)

    // Sources (see hondana.failover).

    /** When a chapter won't load because its site is down, move the series to another source. */
    fun switchSourcesWhenDown() = store.getBoolean("hondana_switch_sources_when_down", true)

    // Content filter (see hondana.safety). Remembered from repo indexes, not user settings, so
    // they're app state and stay out of backups.

    fun blockedExtensionPackages() =
        store.getStringSet(Preference.appStateKey("hondana_blocked_extension_packages"), emptySet())

    fun blockedSourceIds() = store.getStringSet(Preference.appStateKey("hondana_blocked_source_ids"), emptySet())
}

enum class TextEngine {
    /** Claude when an API key is set, otherwise on-device. */
    AUTO,
    ON_DEVICE,
    CLAUDE,
}

enum class TranslationEngine {
    AUTO,
    ON_DEVICE,
    CLAUDE,
}

enum class ClaudeEffort(val apiValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

enum class ReadAloudText {
    /** Read the original lines: listening practice. */
    ORIGINAL,

    /** Read the translation: an audio-drama of the comic. */
    TRANSLATION,
}
