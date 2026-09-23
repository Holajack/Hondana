package hondana.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import hondana.i18n.HMR
import hondana.reader.HondanaReader
import tachiyomi.presentation.core.i18n.stringResource

/** Hondana's row of tools in the reader menu, just above the bottom bar. */
@Composable
fun ReaderTools(
    reader: HondanaReader,
    state: ReaderViewModel.State,
    backgroundColor: Color,
) {
    val readAloud by reader.assistant.readAloud.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolChip(
            icon = if (state.autoScroll) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
            label = stringResource(HMR.strings.hondana_tool_auto_scroll),
            selected = state.autoScroll,
            onClick = reader.autoScroll::toggle,
        )
        ToolChip(
            icon = Icons.Outlined.Translate,
            label = stringResource(HMR.strings.hondana_tool_lens),
            onClick = reader.assistant::openLens,
        )
        ToolChip(
            icon = Icons.Outlined.RecordVoiceOver,
            label = stringResource(HMR.strings.hondana_tool_read_aloud),
            selected = readAloud != null,
            onClick = reader.assistant::toggleReadAloud,
        )
        ToolChip(
            icon = Icons.Outlined.Face,
            label = stringResource(HMR.strings.hondana_tool_voices),
            onClick = { reader.openSheet(HondanaReader.Sheet.VOICES) },
        )
        ToolChip(
            icon = Icons.Outlined.School,
            label = stringResource(HMR.strings.hondana_tool_words),
            onClick = { reader.openSheet(HondanaReader.Sheet.WORDS) },
        )
    }
}

@Composable
private fun ToolChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}
