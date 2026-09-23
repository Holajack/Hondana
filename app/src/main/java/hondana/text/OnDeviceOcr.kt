package hondana.text

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import hondana.core.Languages
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition with ML Kit. Works offline; knows nothing about speakers. */
class OnDeviceOcr {

    private val recognizers = mutableMapOf<Languages.Script, TextRecognizer>()

    private fun recognizer(script: Languages.Script): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(script) {
            when (script) {
                Languages.Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Languages.Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Languages.Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                Languages.Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
        }
    }

    suspend fun read(bitmap: Bitmap, script: Languages.Script, rightToLeft: Boolean): List<TextBlock> {
        val result = recognizer(script).process(InputImage.fromBitmap(bitmap, 0)).await()
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val joiner = if (script == Languages.Script.LATIN) " " else ""
        val blocks = result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val text = block.lines.joinToString(joiner) { it.text }.trim()
            if (text.none { it.isLetterOrDigit() }) return@mapNotNull null
            TextBlock(
                text = text,
                left = (box.left / width).coerceIn(0f, 1f),
                top = (box.top / height).coerceIn(0f, 1f),
                right = (box.right / width).coerceIn(0f, 1f),
                bottom = (box.bottom / height).coerceIn(0f, 1f),
            )
        }
        val merged = if (script == Languages.Script.LATIN) blocks else ReadingOrder.mergeColumns(blocks, rightToLeft)
        return ReadingOrder.sort(merged, rightToLeft)
    }
}

/** Suspends until a Play services [Task] finishes. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}
