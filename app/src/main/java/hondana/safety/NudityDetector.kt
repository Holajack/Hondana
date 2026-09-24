package hondana.safety

import android.content.Context
import android.graphics.Bitmap
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds nudity in pictures, on the phone; nothing is uploaded.
 *
 * The model is GantMan's open NSFW classifier (MobileNet V2, MIT licence, see NOTICE). It rates a
 * 224 x 224 picture as drawings, hentai, neutral, porn or sexy, and "hentai" plus "porn" is the
 * nudity score. [NudityTiles] decides where to look, [NudityResample] shrinks each part.
 *
 * The thresholds were set on ordinary manga pages, anime pictures and webtoon-style strips, where
 * the highest scores were about 0.3 for a whole page, 0.7 for a tile and 0.5 for a strip square;
 * none of them was hidden. A single tile sees little context, so it needs a much clearer score.
 */
object NudityDetector {

    private const val MODEL_ASSET = "hondana/nsfw_mobilenet_v2.tflite"
    private const val INPUT_SIZE = 224
    private const val CLASS_COUNT = 5
    private const val HENTAI = 1
    private const val PORN = 3

    private const val WHOLE_THRESHOLD = 0.45f
    private const val TILE_THRESHOLD = 0.8f
    private const val STRIP_THRESHOLD = 0.6f

    private var interpreter: Interpreter? = null
    private var unavailable = false

    private val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val output = arrayOf(FloatArray(CLASS_COUNT))

    /**
     * Whether [bitmap] shows nudity. [bitmap] must be a software bitmap. [tiles] false checks the
     * picture as a whole only, which suits covers.
     */
    @Synchronized
    fun isNude(context: Context, bitmap: Bitmap, tiles: Boolean): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val plan = NudityTiles.plan(width, height, tiles)
        if (!plan.whole && plan.tileCount == 0) return false
        val model = interpreter(context) ?: return false

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        if (plan.whole) {
            val picture = NudityResample.resize(pixels, width, height, INPUT_SIZE, INPUT_SIZE)
            if (score(model, picture, INPUT_SIZE, 0, 0) >= WHOLE_THRESHOLD) return true
        }
        if (plan.tileCount == 0) return false

        // Shrink once so every tile comes out at the model's size, then cut the tiles out.
        val scaleX = INPUT_SIZE.toDouble() / plan.tileWidth
        val scaleY = INPUT_SIZE.toDouble() / plan.tileHeight
        val scaledWidth = max(INPUT_SIZE, (width * scaleX).roundToInt())
        val scaledHeight = max(INPUT_SIZE, (height * scaleY).roundToInt())
        val scaled = NudityResample.resize(pixels, width, height, scaledWidth, scaledHeight)
        val threshold = if (plan.strip) STRIP_THRESHOLD else TILE_THRESHOLD
        for (top in plan.tops) {
            val y = min((top * scaleY).roundToInt(), scaledHeight - INPUT_SIZE)
            for (left in plan.lefts) {
                val x = min((left * scaleX).roundToInt(), scaledWidth - INPUT_SIZE)
                if (score(model, scaled, scaledWidth, x, y) >= threshold) return true
            }
        }
        return false
    }

    /** The nudity score of the 224 x 224 square at ([x], [y]) of an RGB image [rowWidth] wide. */
    private fun score(model: Interpreter, rgb: FloatArray, rowWidth: Int, x: Int, y: Int): Float {
        input.rewind()
        for (row in y until y + INPUT_SIZE) {
            var index = (row * rowWidth + x) * 3
            repeat(INPUT_SIZE * 3) {
                input.putFloat(rgb[index++] / 255f)
            }
        }
        input.rewind()
        model.run(input, output)
        return output[0][HENTAI] + output[0][PORN]
    }

    private fun interpreter(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (unavailable) return null
        return try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            model.put(bytes)
            model.rewind()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            Interpreter(model, Interpreter.Options().setNumThreads(threads)).also { interpreter = it }
        } catch (e: Throwable) {
            // Without the model nothing can be checked; titles and sources are still filtered.
            unavailable = true
            logcat(LogPriority.ERROR, e) { "Nudity check unavailable" }
            null
        }
    }
}
