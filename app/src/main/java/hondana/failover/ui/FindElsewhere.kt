package hondana.failover.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.toast
import hondana.failover.SourceFailover
import hondana.failover.TitlePageFailover
import hondana.i18n.HMR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DecimalFormat
import tachiyomi.core.common.i18n.stringResource as contextStringResource

/** The title page menu's "Find on another source" action; null where it doesn't apply. */
val LocalFindElsewhere = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Wraps a title's page: puts "Find on another source" in its menu and shows the dialog, also when
 * the page asks for it after a failed refresh ([TitlePageFailover.offerAfterRefreshError]).
 */
@Composable
fun FindElsewhere(
    manga: Manga,
    source: Source,
    onOpenTitle: (Long) -> Unit,
    onSearchByHand: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    // null: closed. true: opened after a failed refresh, when the series may only have moved on its
    // own site. false: opened from the menu, to find it on a different source.
    var afterError by rememberSaveable(manga.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(manga.id) {
        TitlePageFailover.requests.filter { it.mangaId == manga.id }.collect { afterError = it.afterError }
    }
    val action: (() -> Unit)? = if (TitlePageFailover.canSearchElsewhere(source)) {
        { afterError = false }
    } else {
        null
    }
    CompositionLocalProvider(LocalFindElsewhere provides action) {
        content()
    }
    afterError?.let { includeOwnSource ->
        FindElsewhereDialog(
            manga = manga,
            includeOwnSource = includeOwnSource,
            onDismiss = { afterError = null },
            onOpenTitle = {
                afterError = null
                onOpenTitle(it)
            },
            onSearchByHand = {
                afterError = null
                onSearchByHand(it)
            },
        )
    }
}

private sealed interface Phase {
    data object Searching : Phase
    data class Found(val replacement: SourceFailover.Replacement) : Phase
    data object NotFound : Phase
    data class Moving(val sourceName: String) : Phase
}

@Composable
private fun FindElsewhereDialog(
    manga: Manga,
    includeOwnSource: Boolean,
    onDismiss: () -> Unit,
    onOpenTitle: (Long) -> Unit,
    onSearchByHand: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { SourceFailover() }
    var phase by remember { mutableStateOf<Phase>(Phase.Searching) }

    LaunchedEffect(manga.id) {
        phase = try {
            // The copy elsewhere must have the next chapter to read (or the latest one).
            engine.findReplacement(manga, engine.chapterToCheck(manga), includeOwnSource)?.let(Phase::Found)
                ?: Phase.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Looking for ${manga.title} elsewhere failed" }
            Phase.NotFound
        }
    }

    AlertDialog(
        onDismissRequest = { if (phase !is Phase.Moving) onDismiss() },
        title = { Text(stringResource(HMR.strings.hondana_find_elsewhere)) },
        text = {
            when (val current = phase) {
                Phase.Searching -> Working(stringResource(HMR.strings.hondana_failover_searching, manga.title))
                is Phase.Moving -> Working(stringResource(HMR.strings.hondana_failover_moving, current.sourceName))
                Phase.NotFound -> Text(stringResource(HMR.strings.hondana_find_elsewhere_not_found, manga.title))
                is Phase.Found -> Column {
                    val latest = current.replacement.latestChapter
                    Text(
                        if (latest != null) {
                            stringResource(
                                HMR.strings.hondana_find_elsewhere_found,
                                current.replacement.sourceName,
                                chapterNumber.format(latest),
                            )
                        } else {
                            stringResource(HMR.strings.hondana_find_elsewhere_found_unnumbered, current.replacement.sourceName)
                        },
                    )
                    if (manga.favorite) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(HMR.strings.hondana_find_elsewhere_move_note))
                    }
                }
            }
        },
        confirmButton = {
            when (val current = phase) {
                is Phase.Found -> TextButton(
                    onClick = {
                        val replacement = current.replacement
                        phase = Phase.Moving(replacement.sourceName)
                        scope.launch {
                            val moved = try {
                                engine.moveLibraryEntry(manga, replacement.manga)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e) { "Moving ${manga.title} failed" }
                                false
                            }
                            if (moved) {
                                context.toast(
                                    context.contextStringResource(
                                        HMR.strings.hondana_find_elsewhere_moved,
                                        manga.title,
                                        replacement.sourceName,
                                    ),
                                )
                            }
                            onOpenTitle(replacement.manga.id)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (manga.favorite) HMR.strings.hondana_find_elsewhere_move else HMR.strings.hondana_find_elsewhere_open,
                        ),
                    )
                }
                Phase.NotFound -> TextButton(onClick = { onSearchByHand(manga.title) }) {
                    Text(stringResource(HMR.strings.hondana_failover_search_by_hand))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (phase !is Phase.Moving) {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
            }
        },
    )
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(16.dp))
        Text(text)
    }
}

private val chapterNumber = DecimalFormat("#.###")
