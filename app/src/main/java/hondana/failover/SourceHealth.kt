package hondana.failover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import tachiyomi.domain.source.model.SourceNotInstalledException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tells a source that is down apart from a phone that is offline, and remembers which sources
 * failed recently so a switch doesn't land on one of them.
 */
object SourceHealth {

    private const val REMEMBER_DOWN_MS = 30 * 60 * 1000L

    private val downSince = ConcurrentHashMap<Long, Long>()

    fun markDown(sourceId: Long) {
        downSince[sourceId] = System.currentTimeMillis()
    }

    fun isRecentlyDown(sourceId: Long): Boolean =
        downSince[sourceId]?.let { System.currentTimeMillis() - it < REMEMBER_DOWN_MS } ?: false

    /** The phone has a connection Android has checked reaches the internet. */
    fun isOnline(context: Context): Boolean {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Whether [error], raised while a source loaded something, means the site isn't working: it's
     * unreachable, answers with an error, blocks the app, or changed so the extension can't read
     * it. Only meaningful while [isOnline]. A missing extension is not a site problem.
     */
    fun isSiteFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).none { it is CancellationException || it is SourceNotInstalledException }
}
