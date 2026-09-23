package hondana.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import hondana.reader.HondanaReader
import hondana.vocab.WordsDialog

/** Hondana's layer over the reader: auto-scroll controls, read-aloud, the lens and its sheets. */
@Composable
fun HondanaReaderOverlay(reader: HondanaReader, state: ReaderViewModel.State) {
    Box(modifier = Modifier.fillMaxSize()) {
        ReadAloudOverlay(reader)
        AutoScrollControls(
            reader = reader,
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
        )
        LensOverlay(reader)
    }

    val sheet by reader.sheet.collectAsState()
    when (sheet) {
        HondanaReader.Sheet.VOICES -> VoiceCastDialog(reader, onDismiss = reader::closeSheet)
        HondanaReader.Sheet.WORDS -> WordsDialog(onDismiss = reader::closeSheet)
        HondanaReader.Sheet.NONE -> Unit
    }
}
