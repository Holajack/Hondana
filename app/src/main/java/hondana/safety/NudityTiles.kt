package hondana.safety

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where [NudityDetector] looks in an image. Pure Kotlin, so it can be checked on the JVM.
 *
 * The model sees 224 x 224 pixels, and a small figure disappears when a whole manga page is
 * shrunk that far. So besides the whole image, a page is checked in overlapping squares 0.6 of
 * its shorter side. A webtoon strip can't be shrunk whole without squashing it, so it is checked
 * in squares as wide as the strip; each of those is about one panel.
 */
internal object NudityTiles {

    /** Images whose shorter side is smaller than this (icons, separators) aren't checked. */
    const val MIN_SIDE = 100

    /** An image this much longer than it is wide (or the reverse) is a strip. */
    private const val STRIP_RATIO = 2.2f

    /** Tile side on a page, as a share of the page's shorter side. */
    private const val PAGE_TILE_SHARE = 0.6f

    /** Neighbouring tiles start at most this share of a tile apart, so they overlap. */
    private const val STEP = 0.75f

    /** Very long strips get taller tiles rather than more of them. */
    private const val MAX_TILES = 24

    class Plan(
        /** Check the whole image, shrunk to the model's input. False for strips. */
        val whole: Boolean,
        /** Strip squares are a panel's worth of image; page tiles are a part of one. */
        val strip: Boolean,
        val tileWidth: Int,
        val tileHeight: Int,
        val lefts: List<Int>,
        val tops: List<Int>,
    ) {
        val tileCount: Int get() = lefts.size * tops.size
    }

    private val nothing = Plan(whole = false, strip = false, tileWidth = 0, tileHeight = 0, lefts = emptyList(), tops = emptyList())

    /** [tiles] false: the whole image only (covers, which are one picture). */
    fun plan(width: Int, height: Int, tiles: Boolean): Plan {
        val short = min(width, height)
        val long = max(width, height)
        if (short < MIN_SIDE) return nothing
        val strip = long >= short * STRIP_RATIO
        if (!tiles) return Plan(whole = true, strip = false, tileWidth = 0, tileHeight = 0, lefts = emptyList(), tops = emptyList())

        val side = if (strip) short else (short * PAGE_TILE_SHARE).roundToInt()
        var tileWidth = side
        var tileHeight = side
        if (strip) {
            val needed = ceil(long / (1 + STEP * (MAX_TILES - 1))).toInt()
            if (height >= width) tileHeight = max(side, needed) else tileWidth = max(side, needed)
        }
        return Plan(
            whole = !strip,
            strip = strip,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            lefts = starts(width, tileWidth),
            tops = starts(height, tileHeight),
        )
    }

    /** Evenly spaced starts from 0 to [length] - [side], at most [STEP] x [side] apart. */
    private fun starts(length: Int, side: Int): List<Int> {
        if (length <= side) return listOf(0)
        val span = length - side
        val count = ceil(span / (side * STEP)).toInt() + 1
        return List(count) { i -> (span.toLong() * i / (count - 1)).toInt() }
    }
}
