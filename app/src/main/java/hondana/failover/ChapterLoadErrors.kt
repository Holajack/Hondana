package hondana.failover

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.cancellation.CancellationException

/** Chapters the reader couldn't load, reported by `ReaderViewModel` for [hondana.reader.ReaderFailover]. */
object ChapterLoadErrors {

    class Failure(
        val chapterId: Long,
        val error: Throwable,
        /** True when the reader was opening the chapter for the user, false for a preload. */
        val opening: Boolean,
    )

    private val mutableFailures = MutableSharedFlow<Failure>(extraBufferCapacity = 8)
    val failures: SharedFlow<Failure> = mutableFailures.asSharedFlow()

    fun report(chapterId: Long?, error: Throwable, opening: Boolean) {
        if (chapterId == null || error is CancellationException) return
        mutableFailures.tryEmit(Failure(chapterId, error, opening))
    }
}
