package hondana.vocab

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.getSystemService
import hondana.core.VocabEntry

/** Hand-offs from the reader to other apps: clipboard, dictionaries, AnkiDroid. */
object StudyActions {

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Hondana", text))
    }

    /**
     * Offers every installed app that looks up selected text (Takoboto, Akebi, Jisho
     * apps, Google Translate, DeepL...). Falls back to a web dictionary.
     */
    fun lookUp(context: Context, text: String, learningLanguage: String, nativeLanguage: String) {
        val processText = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val hasHandler = context.packageManager.queryIntentActivities(processText, 0).isNotEmpty()
        val intent = if (hasHandler) {
            Intent.createChooser(processText, null)
        } else {
            Intent(Intent.ACTION_VIEW, webDictionary(text, learningLanguage, nativeLanguage))
        }
        start(context, intent)
    }

    private fun webDictionary(text: String, learningLanguage: String, nativeLanguage: String): Uri {
        val query = Uri.encode(text)
        return if (learningLanguage.startsWith("ja") && nativeLanguage.startsWith("en")) {
            Uri.parse("https://jisho.org/search/$query")
        } else {
            Uri.parse("https://translate.google.com/?sl=$learningLanguage&tl=$nativeLanguage&text=$query&op=translate")
        }
    }

    /**
     * Opens AnkiDroid's add-note screen pre-filled with the card. Uses the
     * CREATE_FLASHCARD intent AnkiDroid documents for other apps, and falls back
     * to a plain share if AnkiDroid isn't installed.
     */
    fun sendToAnki(context: Context, entry: VocabEntry) {
        val front = entry.term
        val back = buildString {
            if (entry.reading.isNotBlank() && entry.reading != entry.term) append(entry.reading).append("<br>")
            append(entry.meaning)
            if (entry.sentence.isNotBlank() && entry.sentence != entry.term) {
                append("<br><br>").append(entry.sentence)
                if (entry.sentenceTranslation.isNotBlank()) append("<br><i>").append(entry.sentenceTranslation).append("</i>")
            }
            if (entry.source.isNotBlank()) append("<br><small>").append(entry.source).append("</small>")
        }
        val anki = Intent("org.openintents.action.CREATE_FLASHCARD")
            .setPackage(ANKIDROID_PACKAGE)
            .putExtra("SOURCE_LANGUAGE", entry.language)
            .putExtra("SOURCE_TEXT", front)
            .putExtra("TARGET_TEXT", back)
        try {
            context.startActivity(anki.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            share(context, "$front\n$back".replace("<br>", "\n").replace(Regex("</?[a-z]+>"), ""))
        }
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, null))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Nothing can handle it; the caller's button simply does nothing.
        }
    }

    const val ANKIDROID_PACKAGE = "com.ichi2.anki"
}
