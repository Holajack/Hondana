package hondana.reader.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import hondana.i18n.HMR
import hondana.reader.AutoScrollController
import hondana.reader.HondanaReader
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/** Small floating controls shown while auto-scroll is on and the menu is hidden. */
@Composable
fun AutoScrollControls(
    reader: HondanaReader,
    state: ReaderViewModel.State,
    modifier: Modifier = Modifier,
) {
    if (!state.autoScroll || state.menuVisible) return
    val controller = reader.autoScroll
    val paused by controller.paused.collectAsState()
    val speed by controller.speedPreference().collectAsState()
    val interval by controller.intervalPreference().collectAsState()
    val label = if (controller.usesContinuousScroll()) {
        stringResource(HMR.strings.hondana_autoscroll_speed, speed)
    } else {
        val seconds = if (interval > 0f) interval.roundToInt().coerceAtLeast(1) else AutoScrollController.DEFAULT_PAGE_SECONDS
        stringResource(HMR.strings.hondana_autoscroll_seconds, seconds)
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = controller::togglePause) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(
                        if (paused) HMR.strings.hondana_autoscroll_resume else HMR.strings.hondana_autoscroll_pause,
                    ),
                )
            }
            IconButton(onClick = controller::slower) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(HMR.strings.hondana_autoscroll_slower))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 2.dp),
            )
            IconButton(onClick = controller::faster) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(HMR.strings.hondana_autoscroll_faster))
            }
            IconButton(onClick = { controller.set(false) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(HMR.strings.hondana_autoscroll_stop))
            }
        }
    }
}
