package hondana.reader

import android.graphics.Bitmap
import hondana.ai.ExplanationResult
import hondana.text.PageText
import hondana.text.TextBlock

/** The lens: a frozen screenshot of the reader with the text on it made tappable. */
data class LensState(
    val screenshot: Bitmap,
    val page: PageText? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val selected: Int? = null,
    val showTranslations: Boolean = false,
    val translating: Boolean = false,
    val detail: BlockDetail? = null,
    /** Index of the block being spoken, if any. */
    val speaking: Int? = null,
)

/** Claude's explanation of one block, shown under it in the lens. */
data class BlockDetail(
    val index: Int,
    val loading: Boolean,
    val explanation: ExplanationResult? = null,
    val error: String? = null,
)

/** Hands-free read-aloud on the live reader. */
data class ReadAloudState(
    val preparing: Boolean = true,
    val paused: Boolean = false,
    val block: TextBlock? = null,
    val engine: String = "",
)
