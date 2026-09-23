package hondana.core

import android.app.Application
import hondana.ai.ClaudeModels
import hondana.ai.ClaudeService
import hondana.speech.Speaker
import hondana.speech.VoiceCast
import hondana.text.OnDeviceOcr
import hondana.text.PageReader
import hondana.translate.Translator
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Hondana's services, created on first use. Kept out of Komikku's DI modules to keep upstream files untouched. */
object Hondana {

    private val app: Application by lazy { Injekt.get<Application>() }

    val preferences: HondanaPreferences by lazy { HondanaPreferences(Injekt.get()) }

    val database: HondanaDatabase by lazy { HondanaDatabase(app) }

    val claude: ClaudeService by lazy {
        ClaudeService {
            ClaudeService.Settings(
                apiKey = preferences.claudeApiKey().get().trim(),
                model = preferences.claudeModel().get().trim().ifBlank { ClaudeModels.DEFAULT },
                effort = preferences.claudeEffort().get().apiValue,
            )
        }
    }

    val ocr: OnDeviceOcr by lazy { OnDeviceOcr() }

    val pageReader: PageReader by lazy { PageReader(preferences, claude, ocr, database) }

    val translator: Translator by lazy { Translator(preferences, claude) }

    val speaker: Speaker by lazy { Speaker(app) }

    val voiceCast: VoiceCast by lazy { VoiceCast(database, speaker) }
}
