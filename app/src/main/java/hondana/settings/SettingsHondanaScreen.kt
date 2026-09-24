package hondana.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.util.system.toast
import hondana.ai.ClaudeModels
import hondana.core.ClaudeEffort
import hondana.core.Hondana
import hondana.core.HondanaPreferences
import hondana.core.Languages
import hondana.core.ReadAloudText
import hondana.core.TextEngine
import hondana.core.TranslationEngine
import hondana.i18n.HMR
import hondana.vocab.VocabScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

/** Settings → Reading assistant. */
object SettingsHondanaScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsHondanaScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = HMR.strings.hondana_pref_category_hondana

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences = Hondana.preferences
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow
        return listOf(
            languagesGroup(preferences),
            claudeGroup(preferences, context, scope),
            textGroup(preferences, context, scope),
            readAloudGroup(preferences, context),
            autoScrollGroup(preferences),
            Preference.PreferenceGroup(
                title = stringResource(HMR.strings.hondana_pref_group_words),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(HMR.strings.hondana_pref_open_words),
                        onClick = { navigator.push(VocabScreen()) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(HMR.strings.hondana_pref_group_content_filter),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(HMR.strings.hondana_pref_content_filter_info),
                    ),
                ),
            ),
        )
    }

    @Composable
    private fun languagesGroup(preferences: HondanaPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(HMR.strings.hondana_pref_group_languages),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.learningLanguage(),
                    entries = Languages.learning.associateWith { Languages.displayName(it) }.toImmutableMap(),
                    title = stringResource(HMR.strings.hondana_pref_learning_language),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.nativeLanguage(),
                    entries = Languages.native.associateWith { Languages.displayName(it) }.toImmutableMap(),
                    title = stringResource(HMR.strings.hondana_pref_native_language),
                ),
            ),
        )
    }

    @Composable
    private fun claudeGroup(
        preferences: HondanaPreferences,
        context: Context,
        scope: CoroutineScope,
    ): Preference.PreferenceGroup {
        val apiKey by preferences.claudeApiKey().collectAsState()
        val model by preferences.claudeModel().collectAsState()
        val keySummary = if (apiKey.isBlank()) {
            stringResource(HMR.strings.hondana_pref_claude_api_key_unset)
        } else {
            stringResource(HMR.strings.hondana_pref_claude_api_key_set, "…" + apiKey.trim().takeLast(4))
        }
        val modelEntries = ClaudeModels.options.associate { option ->
            val cost = ClaudeModels.estimatedCostPerPage(option.id)?.let { String.format(Locale.US, "%.3f", it) }
            option.id to if (cost != null) "${option.label} (~$$cost / page)" else option.label
        }.let { entries ->
            if (model in entries) entries else entries + (model to model)
        }
        val modelOption = ClaudeModels.find(model)
        val cost = ClaudeModels.estimatedCostPerPage(model)?.let { String.format(Locale.US, "%.3f", it) } ?: "?"

        return Preference.PreferenceGroup(
            title = stringResource(HMR.strings.hondana_pref_group_claude),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = preferences.claudeApiKey(),
                    title = stringResource(HMR.strings.hondana_pref_claude_api_key),
                    subtitle = keySummary,
                    onValueChanged = { newValue ->
                        Hondana.pageReader.forget()
                        newValue.isNotBlank()
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.claudeModel(),
                    entries = modelEntries.toImmutableMap(),
                    title = stringResource(HMR.strings.hondana_pref_claude_model),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.claudeEffort(),
                    entries = persistentMapOf(
                        ClaudeEffort.LOW to stringResource(HMR.strings.hondana_pref_claude_effort_low),
                        ClaudeEffort.MEDIUM to stringResource(HMR.strings.hondana_pref_claude_effort_medium),
                        ClaudeEffort.HIGH to stringResource(HMR.strings.hondana_pref_claude_effort_high),
                    ),
                    title = stringResource(HMR.strings.hondana_pref_claude_effort),
                    enabled = ClaudeModels.supportsEffort(model),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(HMR.strings.hondana_pref_claude_check),
                    subtitle = stringResource(HMR.strings.hondana_pref_claude_check_summary),
                    enabled = apiKey.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val result = runCatching { withContext(Dispatchers.IO) { Hondana.claude.checkConnection() } }
                            context.toast(
                                result.fold(
                                    onSuccess = { name -> context.getString(android.R.string.ok) + ": " + name },
                                    onFailure = { it.message ?: it.javaClass.simpleName },
                                ),
                            )
                        }
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(HMR.strings.hondana_pref_claude_info, modelOption?.label ?: model, cost),
                ),
            ),
        )
    }

    @Composable
    private fun textGroup(
        preferences: HondanaPreferences,
        context: Context,
        scope: CoroutineScope,
    ): Preference.PreferenceGroup {
        val cleared = stringResource(HMR.strings.hondana_pref_clear_cache_done)
        return Preference.PreferenceGroup(
            title = stringResource(HMR.strings.hondana_pref_group_text),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.textEngine(),
                    entries = persistentMapOf(
                        TextEngine.AUTO to stringResource(HMR.strings.hondana_engine_auto),
                        TextEngine.ON_DEVICE to stringResource(HMR.strings.hondana_engine_on_device),
                        TextEngine.CLAUDE to stringResource(HMR.strings.hondana_engine_claude),
                    ),
                    title = stringResource(HMR.strings.hondana_pref_text_engine),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.translationEngine(),
                    entries = persistentMapOf(
                        TranslationEngine.AUTO to stringResource(HMR.strings.hondana_engine_auto),
                        TranslationEngine.ON_DEVICE to stringResource(HMR.strings.hondana_engine_on_device),
                        TranslationEngine.CLAUDE to stringResource(HMR.strings.hondana_engine_claude),
                    ),
                    title = stringResource(HMR.strings.hondana_pref_translation_engine),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(HMR.strings.hondana_pref_clear_cache),
                    onClick = {
                        scope.launch {
                            Hondana.database.clearPageCache()
                            Hondana.pageReader.forget()
                            context.toast(cleared)
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun readAloudGroup(preferences: HondanaPreferences, context: Context): Preference.PreferenceGroup {
        val rate by preferences.speechRate().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(HMR.strings.hondana_pref_group_read_aloud),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.readAloudText(),
                    entries = persistentMapOf(
                        ReadAloudText.ORIGINAL to stringResource(HMR.strings.hondana_read_aloud_text_original),
                        ReadAloudText.TRANSLATION to stringResource(HMR.strings.hondana_read_aloud_text_translation),
                    ),
                    title = stringResource(HMR.strings.hondana_pref_read_aloud_text),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = rate / 10,
                    valueRange = 5..20,
                    title = stringResource(HMR.strings.hondana_pref_speech_rate),
                    valueString = "$rate%",
                    onValueChanged = { preferences.speechRate().set(it * 10) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.characterVoices(),
                    title = stringResource(HMR.strings.hondana_pref_character_voices),
                    subtitle = stringResource(HMR.strings.hondana_pref_character_voices_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.readAloudAutoAdvance(),
                    title = stringResource(HMR.strings.hondana_pref_read_aloud_auto_advance),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.readSoundEffects(),
                    title = stringResource(HMR.strings.hondana_pref_read_sound_effects),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(HMR.strings.hondana_pref_tts_settings),
                    subtitle = stringResource(HMR.strings.hondana_pref_tts_settings_summary),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun autoScrollGroup(preferences: HondanaPreferences): Preference.PreferenceGroup {
        val speed by preferences.autoScrollSpeed().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(HMR.strings.hondana_pref_group_auto_scroll),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SliderPreference(
                    value = speed,
                    valueRange = 1..30,
                    title = stringResource(HMR.strings.hondana_pref_autoscroll_speed),
                    valueString = stringResource(HMR.strings.hondana_autoscroll_speed, speed),
                    onValueChanged = { preferences.autoScrollSpeed().set(it) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.autoScrollResumeAfterTouch(),
                    title = stringResource(HMR.strings.hondana_pref_autoscroll_resume),
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(HMR.strings.hondana_pref_autoscroll_info),
                ),
            ),
        )
    }
}
