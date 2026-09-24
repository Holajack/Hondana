package hondana.failover

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import hondana.i18n.HMR
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tachiyomi.core.common.i18n.stringResource

/**
 * "Find on another source" on a title's page: the menu item, and the same offer on the error
 * message when refreshing the title fails because its site is down. The dialog lives in
 * [hondana.failover.ui.FindElsewhere].
 */
object TitlePageFailover {

    /** Open the dialog on [mangaId]'s page. [afterError]: the refresh failed, so the series may just have moved. */
    class Request(val mangaId: Long, val afterError: Boolean)

    private val mutableRequests = MutableSharedFlow<Request>(extraBufferCapacity = 1)
    val requests: SharedFlow<Request> = mutableRequests.asSharedFlow()

    fun canSearchElsewhere(source: Source): Boolean = source is HttpSource && source !is MergedSource

    fun request(mangaId: Long, afterError: Boolean) {
        mutableRequests.tryEmit(Request(mangaId, afterError))
    }

    /**
     * Shows [message] for a failed refresh, with a "Find on another source" button when the site
     * is the problem (the phone is online). Returns false when it didn't show anything, so the
     * caller shows its usual message.
     */
    suspend fun offerAfterRefreshError(
        context: Context,
        snackbarHostState: SnackbarHostState,
        mangaId: Long,
        source: Source,
        error: Throwable,
        message: String,
    ): Boolean {
        if (!canSearchElsewhere(source)) return false
        if (!SourceHealth.isOnline(context) || !SourceHealth.isSiteFailure(error)) return false
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = context.stringResource(HMR.strings.hondana_find_elsewhere),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) request(mangaId, afterError = true)
        return true
    }
}
