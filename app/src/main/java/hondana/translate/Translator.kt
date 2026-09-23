package hondana.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import hondana.ai.ClaudeService
import hondana.core.HondanaPreferences
import hondana.core.TranslationEngine
import hondana.core.UserFacingException
import hondana.text.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Translates lines with Claude or with ML Kit's offline models, following the user's engine setting. */
class Translator(
    private val preferences: HondanaPreferences,
    private val claude: ClaudeService,
) {

    fun usesClaude(): Boolean = when (preferences.translationEngine().get()) {
        TranslationEngine.CLAUDE -> true
        TranslationEngine.ON_DEVICE -> false
        TranslationEngine.AUTO -> claude.isConfigured()
    }

    /** Returns one translation per line, same order. */
    suspend fun translate(lines: List<String>, from: String, to: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        return if (usesClaude()) {
            withContext(Dispatchers.IO) { claude.translate(lines, from, to) }
        } else {
            translateOnDevice(lines, from, to)
        }
    }

    private suspend fun translateOnDevice(lines: List<String>, from: String, to: String): List<String> {
        val source = TranslateLanguage.fromLanguageTag(from)
            ?: throw UserFacingException("On-device translation doesn't support ${from.uppercase()}. Add a Claude API key to translate it.")
        val target = TranslateLanguage.fromLanguageTag(to)
            ?: throw UserFacingException("On-device translation doesn't support ${to.uppercase()}.")
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        try {
            // First use downloads a ~30 MB language model; after that it works offline.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return lines.map { line -> translator.translate(line).await() }
        } finally {
            translator.close()
        }
    }
}
