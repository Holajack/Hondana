package hondana.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import hondana.core.UserFacingException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Android text-to-speech with suspend-until-spoken calls, so a read-aloud loop
 * can simply speak one line after another.
 */
class Speaker(context: Context) {

    private val app = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = finish(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
    }

    @Synchronized
    private fun obtainEngine(): TextToSpeech {
        engine?.let { return it }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        val tts = TextToSpeech(app) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
        tts.setOnUtteranceProgressListener(listener)
        engine = tts
        return tts
    }

    private suspend fun readyEngine(): TextToSpeech {
        val tts = obtainEngine()
        val ok = withTimeoutOrNull(10_000) { ready.await() } ?: false
        if (!ok) {
            throw UserFacingException(
                "Text-to-speech isn't ready. Install or enable a speech engine in Android settings.",
            )
        }
        return tts
    }

    /** Installed, offline-capable voices for [locale]'s language, sorted by name. */
    suspend fun voices(locale: Locale): List<Voice> {
        val tts = runCatching { readyEngine() }.getOrNull() ?: return emptyList()
        return withContext(Dispatchers.Main) {
            tts.voices.orEmpty()
                .filter { voice ->
                    voice.locale.language == locale.language &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
                }
                .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
        }
    }

    /** Speaks [text] and suspends until it has been spoken, stopped or cancelled. */
    suspend fun speak(text: String, locale: Locale, voiceName: String?, pitch: Float, rate: Float) {
        if (text.isBlank()) return
        val tts = readyEngine()
        withContext(Dispatchers.Main) {
            val voice = voiceName?.let { name -> tts.voices.orEmpty().firstOrNull { it.name == name } }
            if (voice != null) {
                tts.setVoice(voice)
            } else if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                throw UserFacingException(
                    "No ${locale.getDisplayLanguage(Locale.ENGLISH)} voice is installed. " +
                        "Add one in Android settings → Text-to-speech.",
                )
            }
            tts.setPitch(pitch.coerceIn(0.5f, 2f))
            tts.setSpeechRate(rate.coerceIn(0.4f, 3f))
        }
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine<Unit> { cont ->
            pending[id] = cont
            cont.invokeOnCancellation {
                pending.remove(id)
                tts.stop()
            }
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) finish(id)
        }
    }

    /** Stops whatever is being spoken; the suspended [speak] call returns. */
    fun stop() {
        engine?.stop()
        pending.keys.toList().forEach(::finish)
    }

    private fun finish(utteranceId: String?) {
        val cont = pending.remove(utteranceId ?: return) ?: return
        if (cont.isActive) cont.resume(Unit)
    }
}
