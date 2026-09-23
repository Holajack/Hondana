package hondana.reader.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hondana.i18n.HMR
import hondana.reader.HondanaReader
import hondana.text.TextBlock
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/** While reading aloud: the current speaker and line at the top, and an outline around the bubble. */
@Composable
fun ReadAloudOverlay(reader: HondanaReader) {
    val state by reader.assistant.readAloud.collectAsState()
    val current = state ?: return
    val assistant = reader.assistant

    Box(modifier = Modifier.fillMaxSize()) {
        current.block?.let { block -> BlockOutline(block) }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val block = current.block
                        val heading = when {
                            current.paused -> stringResource(HMR.strings.hondana_read_aloud_paused)
                            block == null -> stringResource(HMR.strings.hondana_read_aloud_finding)
                            block.speaker.isNotBlank() -> block.speaker
                            block.kind == "narration" -> stringResource(HMR.strings.hondana_read_aloud_narrator)
                            else -> stringResource(HMR.strings.hondana_tool_read_aloud)
                        }
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (block != null) {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = assistant::togglePauseReadAloud) {
                        Icon(
                            imageVector = if (current.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = stringResource(
                                if (current.paused) HMR.strings.hondana_read_aloud_resume else HMR.strings.hondana_read_aloud_pause,
                            ),
                        )
                    }
                    IconButton(onClick = assistant::skipLine) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(HMR.strings.hondana_read_aloud_skip))
                    }
                    IconButton(onClick = assistant::stopReadAloud) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(HMR.strings.hondana_read_aloud_stop))
                    }
                }
                if (current.preparing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun BlockOutline(block: TextBlock) {
    if (block.right <= block.left || block.bottom <= block.top) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val pad = with(density) { 4.dp.toPx() }
        val left = block.left * width - pad
        val top = block.top * height - pad
        val boxWidth = (block.right - block.left) * width + pad * 2
        val boxHeight = (block.bottom - block.top) * height + pad * 2
        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { boxWidth.toDp() }, with(density) { boxHeight.toDp() })
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
        )
    }
}
