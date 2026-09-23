package hondana.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import hondana.core.UserFacingException
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ScreenCapture {

    /**
     * Copies exactly what the reader shows, minus Hondana's and the reader's own
     * overlay UI, into a bitmap the size of the reader. Main thread only.
     */
    suspend fun capture(activity: ReaderActivity): Bitmap {
        val content = activity.binding.readerContainer
        val overlay = activity.binding.composeOverlay
        if (content.width <= 0 || content.height <= 0) {
            throw UserFacingException("The page isn't on screen yet.")
        }
        overlay.alpha = 0f
        try {
            // One frame to draw without the overlay, one more for it to reach the screen buffer.
            awaitFrame()
            awaitFrame()
            return pixelCopy(activity.window, content)
        } finally {
            overlay.alpha = 1f
        }
    }

    private suspend fun pixelCopy(window: Window, view: View): Bitmap = suspendCancellableCoroutine { cont ->
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val source = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            window,
            source,
            bitmap,
            { result ->
                if (cont.isActive) {
                    if (result == PixelCopy.SUCCESS) {
                        cont.resume(bitmap)
                    } else {
                        cont.resumeWithException(UserFacingException("Couldn't capture the screen (error $result)."))
                    }
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }
}

/** Scales down so the longest side is at most [maxSide]; returns this bitmap when it already fits. */
fun Bitmap.scaledToMax(maxSide: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return this
    val scale = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}

fun Bitmap.toJpeg(quality: Int = 85): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/**
 * 256-bit average hash of the image. Identical renders of a page give the same
 * value, which is enough to recognise a page the reader has already had read.
 */
fun Bitmap.averageHash(): String {
    val small = Bitmap.createScaledBitmap(this, 16, 16, true)
    val gray = IntArray(256)
    for (y in 0 until 16) {
        for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            gray[y * 16 + x] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
    }
    if (small !== this) small.recycle()
    val mean = gray.average()
    val bits = StringBuilder(64)
    for (i in 0 until 256 step 4) {
        var nibble = 0
        for (j in 0 until 4) {
            if (gray[i + j] > mean) nibble = nibble or (1 shl (3 - j))
        }
        bits.append(Character.forDigit(nibble, 16))
    }
    return "${width}x${height}:$bits"
}
