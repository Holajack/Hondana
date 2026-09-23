package hondana.vocab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hondana.core.Hondana
import hondana.core.Languages
import hondana.core.VocabEntry
import hondana.i18n.HMR
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.i18n.stringResource

/** The saved-words list, used both in the reader and in settings. */
@Composable
fun VocabList(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Hondana.database.vocabVersion.collectAsState()
    var words by remember { mutableStateOf<List<VocabEntry>?>(null) }

    LaunchedEffect(version) {
        words = Hondana.database.vocab()
    }

    val list = words ?: return
    if (list.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(HMR.strings.hondana_words_empty),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(list, key = { it.id }) { entry ->
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (entry.reading.isNotBlank() && entry.reading != entry.term) {
                            Text(
                                text = entry.reading,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val locale = Languages.locale(entry.language.ifBlank { Hondana.preferences.learningLanguage().get() })
                                runCatching { Hondana.speaker.speak(entry.term, locale, null, 1f, 0.9f) }
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(HMR.strings.hondana_action_speak))
                    }
                    IconButton(onClick = { StudyActions.sendToAnki(context, entry) }) {
                        Icon(Icons.Outlined.School, contentDescription = stringResource(HMR.strings.hondana_action_anki))
                    }
                    IconButton(onClick = { scope.launch { Hondana.database.deleteVocab(entry.id) } }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(HMR.strings.hondana_words_delete))
                    }
                }
                if (entry.meaning.isNotBlank()) {
                    Text(entry.meaning, style = MaterialTheme.typography.bodyMedium)
                }
                if (entry.sentence.isNotBlank() && entry.sentence != entry.term) {
                    Text(
                        text = entry.sentence,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (entry.source.isNotBlank()) {
                    Text(
                        text = entry.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    StatusChip(entry, 0, stringResource(HMR.strings.hondana_words_status_new)) { status ->
                        scope.launch { Hondana.database.setVocabStatus(entry.id, status) }
                    }
                    StatusChip(entry, 1, stringResource(HMR.strings.hondana_words_status_learning)) { status ->
                        scope.launch { Hondana.database.setVocabStatus(entry.id, status) }
                    }
                    StatusChip(entry, 2, stringResource(HMR.strings.hondana_words_status_known)) { status ->
                        scope.launch { Hondana.database.setVocabStatus(entry.id, status) }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun StatusChip(entry: VocabEntry, status: Int, label: String, onSet: (Int) -> Unit) {
    FilterChip(
        selected = entry.status == status,
        onClick = { onSet(status) },
        label = { Text(label) },
    )
}

/** Saved words as tab-separated lines: term, reading, meaning, sentence, translation, source. */
suspend fun vocabAsTsv(): String {
    fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')
    return Hondana.database.vocab().joinToString("\n") { entry ->
        listOf(entry.term, entry.reading, entry.meaning, entry.sentence, entry.sentenceTranslation, entry.source)
            .joinToString("\t") { clean(it) }
    }
}
