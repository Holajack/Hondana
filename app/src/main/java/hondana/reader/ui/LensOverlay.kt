package hondana.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hondana.ai.ClaudeModels
import hondana.ai.ExplanationResult
import hondana.core.TextEngine
import hondana.i18n.HMR
import hondana.reader.HondanaReader
import hondana.reader.LensState
import hondana.reader.ReaderAssistant
import hondana.text.TextBlock
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The lens: the frozen screen with every piece of text outlined. Tap one for
 * its card (translation, listen, explain, look up, save, Anki); switch on
 * translations to see the whole page in your language.
 */
@Composable
fun LensOverlay(reader: HondanaReader) {
    val lens by reader.assistant.lens.collectAsState()
    val state = lens ?: return
    val assistant = reader.assistant

    BackHandler {
        if (state.selected != null) assistant.select(null) else assistant.closeLens()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { assistant.select(null) } },
    ) {
        PageWithBlocks(state, assistant)

        LensTopBar(
            state = state,
            assistant = assistant,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        when {
            state.loading -> StatusCard(Modifier.align(Alignment.Center)) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(stringResource(HMR.strings.hondana_lens_reading))
            }
            state.error != null -> StatusCard(Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        OutlinedButton(onClick = { assistant.rescan(TextEngine.ON_DEVICE) }) {
                            Text(stringResource(HMR.strings.hondana_lens_engine_device))
                        }
                        Button(onClick = { assistant.rescan(TextEngine.CLAUDE) }) {
                            Text(stringResource(HMR.strings.hondana_lens_engine_claude))
                        }
                    }
                }
            }
            state.page != null && state.page.blocks.isEmpty() -> StatusCard(Modifier.align(Alignment.Center)) {
                Text(stringResource(HMR.strings.hondana_lens_nothing_found))
            }
        }

        val selected = state.selected
        val block = selected?.let { state.page?.blocks?.getOrNull(it) }
        if (selected != null && block != null) {
            BlockCard(
                index = selected,
                block = block,
                state = state,
                assistant = assistant,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (state.page?.blocks?.isNotEmpty() == true) {
            Text(
                text = stringResource(HMR.strings.hondana_lens_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PageWithBlocks(state: LensState, assistant: ReaderAssistant) {
    val image = remember(state.screenshot) { state.screenshot.asImageBitmap() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / image.width, boxHeight / image.height)
        val drawnWidth = image.width * scale
        val drawnHeight = image.height * scale
        val offsetX = (boxWidth - drawnWidth) / 2f
        val offsetY = (boxHeight - drawnHeight) / 2f

        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))

        state.page?.blocks?.forEachIndexed { index, block ->
            if (block.right <= block.left || block.bottom <= block.top) return@forEachIndexed
            val x = offsetX + block.left * drawnWidth
            val y = offsetY + block.top * drawnHeight
            val w = (block.right - block.left) * drawnWidth
            val h = (block.bottom - block.top) * drawnHeight
            val highlighted = state.selected == index || state.speaking == index
            val shape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .border(
                        width = if (highlighted) 3.dp else 1.5.dp,
                        color = if (highlighted) MaterialTheme.colorScheme.primary else Color(0xFFFFC857),
                        shape = shape,
                    )
                    .background(
                        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color(0x22FFC857),
                        shape,
                    )
                    .clickable { assistant.select(index) },
            ) {
                if (state.showTranslations && block.translation.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.96f), shape)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = block.translation,
                            color = Color.Black,
                            textAlign = TextAlign.Center,
                            fontSize = with(density) { fitTextSize(block.translation, w, h).toSp() },
                            lineHeight = with(density) { (fitTextSize(block.translation, w, h) * 1.15f).toSp() },
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A font size in px that lets [text] roughly fill a [width] x [height] px box. */
private fun fitTextSize(text: String, width: Float, height: Float): Float {
    val chars = text.length.coerceAtLeast(1)
    val size = sqrt((width * height) / (chars * 0.72f))
    return size.coerceIn(10f, min(height * 0.9f, 64f).coerceAtLeast(10f))
}

@Composable
private fun LensTopBar(state: LensState, assistant: ReaderAssistant, modifier: Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = assistant::closeLens) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(HMR.strings.hondana_lens_close))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(HMR.strings.hondana_lens_title), style = MaterialTheme.typography.titleMedium)
                    val page = state.page
                    if (page != null) {
                        val engine = if (page.isFromClaude) {
                            ClaudeModels.find(page.engine.removePrefix("claude:"))?.label
                                ?: stringResource(HMR.strings.hondana_lens_engine_claude)
                        } else {
                            stringResource(HMR.strings.hondana_lens_engine_device)
                        }
                        Text(
                            text = stringResource(HMR.strings.hondana_lens_found, page.blocks.size) + " · " + engine,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = assistant::toggleTranslations, enabled = state.page != null) {
                    Icon(
                        imageVector = Icons.Outlined.Translate,
                        contentDescription = stringResource(
                            if (state.showTranslations) HMR.strings.hondana_lens_hide_translations else HMR.strings.hondana_lens_show_translations,
                        ),
                        tint = if (state.showTranslations) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (state.speaking != null) {
                    IconButton(onClick = assistant::stopSpeaking) {
                        Icon(Icons.Outlined.Stop, contentDescription = stringResource(HMR.strings.hondana_lens_stop))
                    }
                } else {
                    IconButton(onClick = assistant::speakAll, enabled = state.page?.blocks?.isNotEmpty() == true) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(HMR.strings.hondana_lens_read_all))
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(HMR.strings.hondana_lens_rescan_claude)) },
                            onClick = {
                                menuOpen = false
                                assistant.rescan(TextEngine.CLAUDE)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(HMR.strings.hondana_lens_rescan_device)) },
                            onClick = {
                                menuOpen = false
                                assistant.rescan(TextEngine.ON_DEVICE)
                            },
                        )
                    }
                }
            }
            if (state.translating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StatusCard(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.padding(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

@Composable
private fun BlockCard(
    index: Int,
    block: TextBlock,
    state: LensState,
    assistant: ReaderAssistant,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            // Swallow taps so they don't close the card.
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = listOf(block.speaker, kindLabel(block.kind))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { assistant.select(null) }) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                }
            }
            SelectionContainer {
                Text(block.text, style = MaterialTheme.typography.headlineSmall)
            }
            if (block.reading.isNotBlank() && block.reading != block.text) {
                Text(
                    text = block.reading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (block.translation.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = block.translation,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            FlowRow(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionChip(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(HMR.strings.hondana_action_speak)) {
                    assistant.speak(index)
                }
                if (block.translation.isNotBlank()) {
                    ActionChip(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(HMR.strings.hondana_action_speak_translation)) {
                        assistant.speak(index, translation = true)
                    }
                } else {
                    ActionChip(Icons.Outlined.Translate, stringResource(HMR.strings.hondana_action_translate)) {
                        assistant.translate(index)
                    }
                }
                ActionChip(Icons.Outlined.AutoAwesome, stringResource(HMR.strings.hondana_action_explain)) {
                    assistant.explain(index)
                }
                ActionChip(Icons.Outlined.Search, stringResource(HMR.strings.hondana_action_look_up)) {
                    assistant.lookUp(block.text)
                }
                ActionChip(Icons.Outlined.ContentCopy, stringResource(HMR.strings.hondana_action_copy)) {
                    assistant.copy(index)
                }
                ActionChip(Icons.Outlined.BookmarkAdd, stringResource(HMR.strings.hondana_action_save)) {
                    assistant.saveLine(index)
                }
                ActionChip(Icons.Outlined.School, stringResource(HMR.strings.hondana_action_anki)) {
                    assistant.sendLineToAnki(index)
                }
            }

            val detail = state.detail?.takeIf { it.index == index }
            if (detail != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                when {
                    detail.loading -> {
                        Text(stringResource(HMR.strings.hondana_explain_loading), style = MaterialTheme.typography.labelLarge)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    detail.error != null -> Text(detail.error, color = MaterialTheme.colorScheme.error)
                    detail.explanation != null -> Explanation(
                        explanation = detail.explanation,
                        onLookUp = assistant::lookUp,
                        onSaveWord = { word -> assistant.saveWord(index, word) },
                    )
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "speech" -> stringResource(HMR.strings.hondana_kind_speech)
    "thought" -> stringResource(HMR.strings.hondana_kind_thought)
    "narration" -> stringResource(HMR.strings.hondana_kind_narration)
    "sfx" -> stringResource(HMR.strings.hondana_kind_sfx)
    "sign" -> stringResource(HMR.strings.hondana_kind_sign)
    else -> stringResource(HMR.strings.hondana_kind_other)
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
private fun Explanation(
    explanation: ExplanationResult,
    onLookUp: (String) -> Unit,
    onSaveWord: (hondana.ai.ExplainedWord) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (explanation.literal.isNotBlank()) {
            SectionTitle(stringResource(HMR.strings.hondana_explain_literal))
            Text(explanation.literal, style = MaterialTheme.typography.bodyMedium)
        }
        if (explanation.words.isNotEmpty()) {
            SectionTitle(stringResource(HMR.strings.hondana_explain_words))
            explanation.words.forEach { word ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLookUp(word.dictionaryForm.ifBlank { word.word }) },
                ) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (word.reading.isNotBlank() && word.reading != word.word) {
                                Text(
                                    text = "  ${word.reading}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val form = if (word.dictionaryForm.isNotBlank() && word.dictionaryForm != word.word) {
                            "${word.dictionaryForm} · "
                        } else {
                            ""
                        }
                        Text(
                            text = form + listOf(word.partOfSpeech, word.meaning).filter { it.isNotBlank() }.joinToString(" — "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IconButton(onClick = { onSaveWord(word) }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(HMR.strings.hondana_action_save_word))
                    }
                }
            }
        }
        if (explanation.grammar.isNotEmpty()) {
            SectionTitle(stringResource(HMR.strings.hondana_explain_grammar))
            explanation.grammar.forEach { point ->
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(point.pattern, style = MaterialTheme.typography.titleSmall)
                    Text(point.explanation, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (explanation.notes.isNotBlank()) {
            SectionTitle(stringResource(HMR.strings.hondana_explain_notes))
            Text(explanation.notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}
