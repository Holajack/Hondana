package hondana.safety

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import coil3.BitmapImage
import coil3.Image
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.toBitmap
import hondana.i18n.HMR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Checks every picture the app loads through Coil (covers, page previews, tracker artwork) with
 * [NudityDetector] and shows a "hidden" card instead of one that shows nudity. Reader pages
 * are checked by the reader itself ([NudityScreen]), before they are shown.
 */
class NudityImageInterceptor(private val context: Context) : Interceptor {

    /** Verdicts by image, so scrolling back over a cover doesn't check it again. */
    private val verdicts = LruCache<String, Boolean>(2048)

    // One check at a time; the model itself uses several threads.
    private val checking = Dispatchers.Default.limitedParallelism(1)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result !is SuccessResult) return result
        val data = result.request.data
        if (data is BufferedSource || data is ByteBuffer || data is ByteArray) return result
        val image = result.image
        if (min(image.width, image.height) < NudityTiles.MIN_SIDE) return result

        val key = result.memoryCacheKey?.key ?: result.diskCacheKey ?: data.toString()
        val nude = verdicts.get(key) ?: withContext(checking) { check(image) }.also { verdicts.put(key, it) }
        if (!nude) return result
        val text = context.stringResource(HMR.strings.hondana_nudity_cover_hidden)
        return result.copy(image = NudityScreen.hiddenCard(image.width, image.height, text).asImage())
    }

    private fun check(image: Image): Boolean {
        val original = (image as? BitmapImage)?.bitmap
        val bitmap = try {
            when {
                original != null && original.config != Bitmap.Config.HARDWARE -> original
                original != null -> original.copy(Bitmap.Config.ARGB_8888, false)
                else -> image.toBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Couldn't read a picture to check it" }
            null
        } ?: return false
        return try {
            NudityDetector.isNude(context, bitmap, tiles = false)
        } finally {
            if (bitmap !== original) bitmap.recycle()
        }
    }
}
