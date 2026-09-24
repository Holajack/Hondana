package hondana.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import hondana.i18n.HMR
import hondana.reader.ReaderFailover
import hondana.reader.ReaderFailover.Status
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** What [ReaderFailover] is doing: looking elsewhere, moving, nothing found, or a chapter ready elsewhere. */
@Composable
fun FailoverCard(failover: ReaderFailover, state: ReaderViewModel.State) {
    val status by failover.status.collectAsState()
    when (val current = status) {
        Status.Idle -> Unit
        is Status.Searching -> CenteredCard {
            WorkingRow(
                title = stringResource(HMR.strings.hondana_failover_not_responding, current.sourceName),
                body = stringResource(HMR.strings.hondana_failover_searching, current.title),
            )
            Buttons {
                TextButton(onClick = failover::dismiss) { Text(stringResource(MR.strings.action_cancel)) }
            }
        }
        is Status.Moving -> CenteredCard {
            WorkingRow(title = stringResource(HMR.strings.hondana_failover_moving, current.targetName), body = null)
        }
        is Status.NotFound -> CenteredCard {
            Text(
                text = stringResource(HMR.strings.hondana_failover_not_responding, current.sourceName),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                text = stringResource(HMR.strings.hondana_failover_not_found, current.title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Buttons {
                TextButton(onClick = { failover.searchByHand(current.title) }) {
                    Text(stringResource(HMR.strings.hondana_failover_search_by_hand))
                }
                TextButton(onClick = failover::dismiss) { Text(stringResource(MR.strings.action_close)) }
            }
        }
        // Offered once the current chapter is finished; the reader behind stays usable.
        is Status.Ready -> if (state.totalPages > 0 && state.currentPage >= state.totalPages) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                CardSurface(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                    Text(
                        text = stringResource(
                            HMR.strings.hondana_failover_next_chapter,
                            current.sourceName,
                            current.replacement.sourceName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Buttons {
                        TextButton(onClick = failover::dismiss) { Text(stringResource(MR.strings.action_close)) }
                        Button(onClick = failover::continueOnReplacement) {
                            Text(stringResource(HMR.strings.hondana_failover_continue_on, current.replacement.sourceName))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredCard(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CardSurface(modifier = Modifier.padding(24.dp), content = content)
    }
}

@Composable
private fun CardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.widthIn(max = 440.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp)) {
            content()
        }
    }
}

@Composable
private fun WorkingRow(title: String, body: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (body != null) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (body == null) Spacer(Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
