package hondana.text

import kotlin.math.max
import kotlin.math.min

/** Layout heuristics for text found by on-device recognition, which returns blocks in no useful order. */
object ReadingOrder {

    /**
     * Rows from top to bottom; within a row right to left for manga, else left to right.
     * Blocks share a row when they overlap vertically for a good part of the shorter one.
     */
    fun sort(blocks: List<TextBlock>, rightToLeft: Boolean): List<TextBlock> {
        if (blocks.size < 2) return blocks
        val rows = mutableListOf<MutableList<TextBlock>>()
        for (block in blocks.sortedBy { it.top }) {
            val row = rows.lastOrNull()
            if (row != null && sharesRow(row, block)) row.add(block) else rows.add(mutableListOf(block))
        }
        return rows.flatMap { row ->
            if (rightToLeft) row.sortedByDescending { it.centerX } else row.sortedBy { it.centerX }
        }
    }

    private fun sharesRow(row: List<TextBlock>, block: TextBlock): Boolean {
        val rowTop = row.minOf { it.top }
        val rowBottom = row.maxOf { it.bottom }
        val overlap = min(rowBottom, block.bottom) - max(rowTop, block.top)
        val shorter = min(rowBottom - rowTop, block.bottom - block.top)
        return shorter > 0f && overlap > 0.4f * shorter
    }

    /**
     * Vertical CJK text comes back one column per block. Join neighbouring tall,
     * narrow columns that sit side by side into one bubble, reading the columns
     * right to left when [rightToLeft].
     */
    fun mergeColumns(blocks: List<TextBlock>, rightToLeft: Boolean): List<TextBlock> {
        if (blocks.size < 2) return blocks
        val parent = IntArray(blocks.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun isColumn(b: TextBlock) = (b.bottom - b.top) > (b.right - b.left) * 1.2f

        for (i in blocks.indices) {
            for (j in i + 1 until blocks.size) {
                val a = blocks[i]
                val b = blocks[j]
                if (!isColumn(a) || !isColumn(b)) continue
                val gap = max(a.left, b.left) - min(a.right, b.right)
                val width = max(a.right - a.left, b.right - b.left)
                val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
                val shorter = min(a.bottom - a.top, b.bottom - b.top)
                if (gap < width * 0.9f && overlap > 0.3f * shorter) {
                    parent[find(i)] = find(j)
                }
            }
        }

        return blocks.indices
            .groupBy { find(it) }
            .values
            .map { members ->
                val group = members.map { blocks[it] }
                if (group.size == 1) {
                    group.first()
                } else {
                    val ordered = if (rightToLeft) group.sortedByDescending { it.centerX } else group.sortedBy { it.centerX }
                    TextBlock(
                        text = ordered.joinToString("") { it.text },
                        left = group.minOf { it.left },
                        top = group.minOf { it.top },
                        right = group.maxOf { it.right },
                        bottom = group.maxOf { it.bottom },
                        kind = "speech",
                    )
                }
            }
    }
}
