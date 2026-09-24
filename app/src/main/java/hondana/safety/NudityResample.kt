package hondana.safety

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lanczos-3 resizing, computed the way Pillow does it. Pure Kotlin, so it can be checked on the JVM.
 *
 * The shrinking method matters a lot: Android's bilinear scaling turns manga screentones into
 * noise that the model reads as skin, while Lanczos keeps them tidy. On a set of ordinary manga
 * pages and anime pictures, bilinear-style shrinking scored some pages of cats and furry
 * mascots as explicit; Lanczos scored none of them above the thresholds.
 */
internal object NudityResample {

    private const val SUPPORT = 3.0

    private class Weights(val starts: IntArray, val counts: IntArray, val values: FloatArray, val stride: Int)

    /**
     * Resizes ARGB [pixels] ([width] x [height]) to [outWidth] x [outHeight]. Returns red, green
     * and blue per pixel, row by row, from 0 to 255. Transparency is shown over white.
     */
    fun resize(pixels: IntArray, width: Int, height: Int, outWidth: Int, outHeight: Int): FloatArray {
        val across = weights(width, outWidth)
        val down = weights(height, outHeight)

        // Across each row first, then down each column.
        val rows = FloatArray(outWidth * height * 3)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until outWidth) {
                val start = row + across.starts[x]
                val base = x * across.stride
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in 0 until across.counts[x]) {
                    val weight = across.values[base + k]
                    val pixel = pixels[start + k]
                    val alpha = pixel ushr 24
                    if (alpha == 0xFF) {
                        r += weight * (pixel shr 16 and 0xFF)
                        g += weight * (pixel shr 8 and 0xFF)
                        b += weight * (pixel and 0xFF)
                    } else {
                        val opacity = alpha / 255f
                        val white = 255f * (1f - opacity)
                        r += weight * ((pixel shr 16 and 0xFF) * opacity + white)
                        g += weight * ((pixel shr 8 and 0xFF) * opacity + white)
                        b += weight * ((pixel and 0xFF) * opacity + white)
                    }
                }
                val out = (y * outWidth + x) * 3
                rows[out] = r
                rows[out + 1] = g
                rows[out + 2] = b
            }
        }

        val result = FloatArray(outWidth * outHeight * 3)
        for (y in 0 until outHeight) {
            val start = down.starts[y]
            val base = y * down.stride
            val out = y * outWidth * 3
            for (k in 0 until down.counts[y]) {
                val weight = down.values[base + k]
                val source = (start + k) * outWidth * 3
                for (i in 0 until outWidth * 3) {
                    result[out + i] += weight * rows[source + i]
                }
            }
            for (i in out until out + outWidth * 3) {
                result[i] = result[i].coerceIn(0f, 255f)
            }
        }
        return result
    }

    /** Pillow's `precompute_coeffs` for the Lanczos filter. */
    private fun weights(inSize: Int, outSize: Int): Weights {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = SUPPORT * filterScale
        val stride = ceil(support).toInt() * 2 + 1
        val starts = IntArray(outSize)
        val counts = IntArray(outSize)
        val values = FloatArray(outSize * stride)
        val sums = DoubleArray(stride)
        for (i in 0 until outSize) {
            val center = (i + 0.5) * scale
            val first = max((center - support + 0.5).toInt(), 0)
            val count = min((center + support + 0.5).toInt(), inSize) - first
            var total = 0.0
            for (k in 0 until count) {
                val weight = lanczos((k + first - center + 0.5) / filterScale)
                sums[k] = weight
                total += weight
            }
            for (k in 0 until count) {
                values[i * stride + k] = (if (total != 0.0) sums[k] / total else 0.0).toFloat()
            }
            starts[i] = first
            counts[i] = count
        }
        return Weights(starts, counts, values, stride)
    }

    private fun lanczos(x: Double): Double =
        if (x > -SUPPORT && x < SUPPORT) sinc(x) * sinc(x / SUPPORT) else 0.0

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val angle = PI * x
        return sin(angle) / angle
    }
}
