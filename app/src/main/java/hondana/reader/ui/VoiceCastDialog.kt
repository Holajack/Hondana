package hondana.reader.ui

import android.speech.tts.Voice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hondana.core.CastEntry
import hondana.core.Hondana
import hondana.core.Languages
import hondana.i18n.HMR
import hondana.reader.HondanaReader
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.i18n.stringResource

/** Edit the voice, pitch and pace of every character Claude has met in this series. */
@Composable
fun VoiceCastDialog(reader: HondanaReader, onDismiss: () -> Unit) {
    val mangaId = reader.activity.viewModel.manga?.id
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<CastEntry>?>(null) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    val locale = remember { Languages.locale(Hondana.preferences.learningLanguage().get()) }

    LaunchedEffect(mangaId) {
        voices = Hondana.speaker.voices(locale)
        entries = mangaId?.let { Hondana.voiceCast.entries(it) }.orEmpty()
    }

    fun update(entry: CastEntry) {
        val id = mangaId ?: return
        entries = entries?.map { if (it.character == entry.character) entry else it }
        scope.launch { Hondana.voiceCast.save(id, entry) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(HMR.strings.hondana_action_done)) }
        },
        title = { Text(stringResource(HMR.strings.hondana_voices_title)) },
        text = {
            val list = entries
            when {
                mangaId == null -> Text(stringResource(HMR.strings.hondana_voices_no_manga))
                list == null -> Unit
                list.isEmpty() -> Text(stringResource(HMR.strings.hondana_voices_empty))
                else -> LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(list, key = { it.character }) { entry ->
                        CastRow(
                            entry = entry,
                            voices = voices,
                            onChange = ::update,
                            onTest = {
                                scope.launch {
                                    runCatching {
                                        Hondana.speaker.speak(
                                            text = entry.character,
                                            locale = locale,
                                            voiceName = entry.voice,
                                            pitch = entry.pitch,
                                            rate = entry.rate,
                                        )
                                    }
                                }
                            },
                            onRemove = {
                                entries = entries?.filterNot { it.character == entry.character }
                                scope.launch { Hondana.voiceCast.remove(mangaId, entry.character) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

@Composable
private fun CastRow(
    entry: CastEntry,
    voices: List<Voice>,
    onChange: (CastEntry) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.character, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.note.isNotBlank()) {
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onTest) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(HMR.strings.hondana_voices_test))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(HMR.strings.hondana_voices_remove))
            }
        }
        Box {
            val voiceIndex = voices.indexOfFirst { it.name == entry.voice }
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (voiceIndex >= 0) {
                        stringResource(HMR.strings.hondana_voices_voice) + " ${voiceIndex + 1} · ${voices[voiceIndex].name}"
                    } else {
                        stringResource(HMR.strings.hondana_voices_default_voice)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(HMR.strings.hondana_voices_default_voice)) },
                    onClick = {
                        menuOpen = false
                        onChange(entry.copy(voice = null))
                    },
                )
                voices.forEachIndexed { index, voice ->
                    DropdownMenuItem(
                        text = { Text(stringResource(HMR.strings.hondana_voices_voice) + " ${index + 1} · ${voice.name}") },
                        onClick = {
                            menuOpen = false
                            onChange(entry.copy(voice = voice.name))
                        },
                    )
                }
            }
        }
        LabeledSlider(
            label = stringResource(HMR.strings.hondana_voices_pitch),
            value = entry.pitch,
            range = 0.5f..2f,
            onChange = { onChange(entry.copy(pitch = it)) },
        )
        LabeledSlider(
            label = stringResource(HMR.strings.hondana_voices_pace),
            value = entry.rate,
            range = 0.5f..1.8f,
            onChange = { onChange(entry.copy(rate = it)) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(0.35f),
        )
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(0.65f),
        )
    }
}
