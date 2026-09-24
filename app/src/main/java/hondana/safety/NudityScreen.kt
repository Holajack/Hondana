package hondana.safety

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.core.graphics.withTranslation
import eu.kanade.tachiyomi.util.system.toast
import hondana.i18n.HMR
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import okio.blackholeSink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import java.util.Collections
import java.util.WeakHashMap
import java.util.zip.CRC32
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Checks reader pages with [NudityDetector] before they are shown, and draws the card that
 * replaces a page or cover showing nudity. The reader's page holders call [screenPage] while
 * they load a page in the background; covers go through [NudityImageInterceptor].
 */
object NudityScreen {

    /** Pages are decoded at least this large on their shorter side, so tiles keep their detail. */
    private const val MIN_SHORT_SIDE = 400
    private const val MAX_PIXELS = 4_000_000

    /** Mid grey, so the reader's "crop borders" doesn't trim the card down to its text. */
    private const val CARD_COLOR = 0xFF4A4A4A.toInt()
    private const val TEXT_COLOR = 0xFFE8E8E8.toInt()

    private const val KEY_BYTES = 256L * 1024

    /** Height / width of a hidden page, or 0 for a page that is fine. */
    private val verdicts = LruCache<Long, Float>(512)

    /** Pages shown as the card, so the reader won't save, share or use them as a cover. */
    private val hiddenPages: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    /**
     * Returns [source] unchanged, or the "hidden" card in its place when the page shows nudity.
     * [pages] are the reader pages [source] shows: two for a spread merged into one image.
     */
    fun screenPage(context: Context, source: BufferedSource, vararg pages: Any?): BufferedSource {
        val key = contentKey(source)
        val shape = verdicts.get(key) ?: check(context, source)?.also { verdicts.put(key, it) } ?: 0f
        if (shape == 0f) return source
        pages.forEach { if (it != null) hiddenPages += it }
        return hiddenPageImage(context, shape)
    }

    /** Whether the reader showed [page] as the "hidden" card. */
    fun isHidden(page: Any?): Boolean = page != null && page in hiddenPages

    /** True, after saying so, when one of [pages] is hidden: it can't be saved, shared or set as cover. */
    fun refuseHidden(context: Context, vararg pages: Any?): Boolean {
        if (pages.none { isHidden(it) }) return false
        context.toast(HMR.strings.hondana_nudity_page_action_blocked)
        return true
    }

    /** The page's height / width when it shows nudity, 0 when it doesn't, null when it can't be checked. */
    private fun check(context: Context, source: BufferedSource): Float? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = decode(source) ?: return null
            if (NudityDetector.isNude(context, bitmap, tiles = true)) {
                logcat(LogPriority.INFO) { "Hid a page that shows nudity" }
                bitmap.height.toFloat() / bitmap.width
            } else {
                0f
            }
        } catch (e: Throwable) {
            // Out of memory on a huge page, say. The page is shown and checked again next time.
            logcat(LogPriority.WARN, e) { "Couldn't check a page" }
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /** A software bitmap of the page, shrunk while decoding where that is safe. */
    private fun decode(source: BufferedSource): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(source.peek().inputStream(), null, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            // JPEG and WebP shrink smoothly while decoding; PNG and GIF drop pixels, which makes
            // screentones noisy, so those are only shrunk when very large.
            val smooth = bounds.outMimeType == "image/jpeg" || bounds.outMimeType == "image/webp"
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, smooth)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(source.peek().inputStream(), null, options)?.let { return it }
        }
        // Formats Android can't open itself, such as JPEG XL and (before Android 12) AVIF.
        val decoder = ImageDecoder.newInstance(source.peek().inputStream()) ?: return null
        return try {
            decoder.decode(sampleSize = sampleSize(decoder.width, decoder.height, smooth = true))
        } finally {
            decoder.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int, smooth: Boolean): Int {
        var size = 1
        while (
            (smooth && min(width, height) / (size * 2) >= MIN_SHORT_SIDE) ||
            width.toLong() * height / (size.toLong() * size) > MAX_PIXELS
        ) {
            size *= 2
        }
        return size
    }

    /** Identifies a page by its length and a checksum of its first 256 KB. */
    private fun contentKey(source: BufferedSource): Long {
        val length = (source as? Buffer)?.size ?: source.peek().use { it.readAll(blackholeSink()) }
        val head = source.peek().use { peek ->
            peek.request(KEY_BYTES)
            peek.readByteArray(min(peek.buffer.size, KEY_BYTES))
        }
        val crc = CRC32().apply { update(head) }.value
        return (length shl 32) xor crc
    }

    /** The card as a PNG with about the shape of the page it replaces. */
    private fun hiddenPageImage(context: Context, shape: Float): BufferedSource {
        val width = 1080
        val height = (width * shape.coerceIn(0.5f, 3f)).roundToInt()
        val card = hiddenCard(width, height, context.stringResource(HMR.strings.hondana_nudity_page_hidden))
        val buffer = Buffer()
        card.compress(Bitmap.CompressFormat.PNG, 100, buffer.outputStream())
        card.recycle()
        return buffer
    }

    /** A grey card with [text] in the middle. */
    fun hiddenCard(width: Int, height: Int, text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(CARD_COLOR)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = width / 14f
        }
        val textWidth = (width * 0.8f).roundToInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.withTranslation((width - textWidth) / 2f, (height - layout.height) / 2f) {
            layout.draw(this)
        }
        return bitmap
    }
}
