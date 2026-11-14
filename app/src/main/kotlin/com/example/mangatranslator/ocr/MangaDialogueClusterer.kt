package com.example.mangatranslator.ocr

import android.graphics.Point
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Clusters fragmented text columns into cohesive manga speech bubble units.
 *
 * Traditional vertical Japanese manga dialogue is often split by OCR engines
 * into individual single-line TextBlocks. This clusterer groups parallel vertical
 * columns belonging to the same speech bubble, orders them Right-to-Left (RTL),
 * and strips furigana ruby text so NMT engines receive complete, coherent sentences.
 */
object MangaDialogueClusterer {

    private val Rect.rectWidth: Int get() = right - left
    private val Rect.rectHeight: Int get() = bottom - top
    private val Rect.rectCenterX: Int get() = left + (right - left) / 2
    private val Rect.rectCenterY: Int get() = top + (bottom - top) / 2

    /**
     * Clusters nearby vertical columns or broken text blocks that belong to the same speech bubble.
     * Orders vertical manga text strictly from Right to Left (descending X coordinates).
     */
    fun clusterDialogueBlocks(
        blocks: List<RecognizedTextBlock>,
        language: OcrLanguage
    ): List<RecognizedTextBlock> {
        if (blocks.size <= 1) return blocks

        // Filter out furigana (ruby text) lines before clustering
        val sanitizedBlocks = blocks.map { sanitizeFurigana(it) }

        val n = sanitizedBlocks.size
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootJ] = rootI
            }
        }

        // Test pairs for speech bubble co-membership
        for (i in 0 until n) {
            val a = sanitizedBlocks[i]
            for (j in i + 1 until n) {
                val b = sanitizedBlocks[j]
                if (shouldMerge(a, b, language)) {
                    union(i, j)
                }
            }
        }

        // Group by connected component root
        val groups = mutableMapOf<Int, MutableList<RecognizedTextBlock>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(sanitizedBlocks[i])
        }

        return groups.values.map { cluster ->
            if (cluster.size == 1) {
                cluster[0]
            } else {
                mergeCluster(cluster, language)
            }
        }
    }

    private fun rectsIntersect(a: Rect, b: Rect): Boolean {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }

    private fun shouldMerge(a: RecognizedTextBlock, b: RecognizedTextBlock, language: OcrLanguage): Boolean {
        val boxA = a.boundingBox
        val boxB = b.boundingBox

        // Directly intersecting bounding boxes
        if (rectsIntersect(boxA, boxB)) return true

        val isVertical = a.isVertical || b.isVertical || (language == OcrLanguage.JAPANESE)

        if (isVertical) {
            // For vertical text, columns run parallel vertically with a small horizontal gap
            val verticalOverlap = max(0, min(boxA.bottom, boxB.bottom) - max(boxA.top, boxB.top))
            val minHeight = min(boxA.rectHeight, boxB.rectHeight)

            // Must share significant vertical height (at least 35% of the shorter column)
            if (minHeight > 0 && verticalOverlap.toFloat() / minHeight >= 0.35f) {
                val horizontalGap = max(0, max(boxA.left, boxB.left) - min(boxA.right, boxB.right))
                // Maximum allowable gap between columns in the same speech bubble
                val avgWidth = (boxA.rectWidth + boxB.rectWidth) / 2f
                val maxGap = max(50f, avgWidth * 2.2f)

                if (horizontalGap <= maxGap) {
                    return true
                }
            }
        } else {
            // For horizontal text, lines are stacked vertically with horizontal overlap
            val horizontalOverlap = max(0, min(boxA.right, boxB.right) - max(boxA.left, boxB.left))
            val minWidth = min(boxA.rectWidth, boxB.rectWidth)

            if (minWidth > 0 && horizontalOverlap.toFloat() / minWidth >= 0.4f) {
                val verticalGap = max(0, max(boxA.top, boxB.top) - min(boxA.bottom, boxB.bottom))
                val avgHeight = (boxA.rectHeight + boxB.rectHeight) / 2f
                val maxGap = max(40f, avgHeight * 1.8f)

                if (verticalGap <= maxGap) {
                    return true
                }
            }
        }

        return false
    }

    private fun mergeCluster(cluster: List<RecognizedTextBlock>, language: OcrLanguage): RecognizedTextBlock {
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE

        val allLines = mutableListOf<RecognizedLine>()
        val allPoints = mutableListOf<Point>()
        var hasVertical = false

        for (block in cluster) {
            val b = block.boundingBox
            minLeft = min(minLeft, b.left)
            minTop = min(minTop, b.top)
            maxRight = max(maxRight, b.right)
            maxBottom = max(maxBottom, b.bottom)

            allLines.addAll(block.lines)
            allPoints.addAll(block.cornerPoints)
            if (block.isVertical) hasVertical = true
        }

        val mergedBox = Rect(minLeft, minTop, maxRight, maxBottom)
        val isVertical = hasVertical || (language == OcrLanguage.JAPANESE && mergedBox.rectHeight >= mergedBox.rectWidth * 0.9f)

        // For vertical Japanese text, sort lines from Right to Left (RTL: descending centerX)
        val sortedLines = if (isVertical) {
            allLines.sortedByDescending { it.boundingBox?.rectCenterX ?: 0 }
        } else {
            allLines.sortedBy { it.boundingBox?.rectCenterY ?: 0 }
        }

        val combinedText = sortedLines.joinToString("\n") { it.text }

        return RecognizedTextBlock(
            text = combinedText,
            boundingBox = mergedBox,
            cornerPoints = allPoints,
            lines = sortedLines,
            isVertical = isVertical
        )
    }

    /**
     * Filters out furigana (tiny ruby annotation characters) within a block.
     * Furigana lines are exceptionally narrow and sit beside normal dialogue.
     */
    private fun sanitizeFurigana(block: RecognizedTextBlock): RecognizedTextBlock {
        if (block.lines.size <= 1) return block

        val medianWidth = block.lines.mapNotNull { it.boundingBox?.rectWidth }.sorted().let {
            if (it.isEmpty()) 0 else it[it.size / 2]
        }

        // If median width is reasonable, any line whose width is < 30% of median is likely furigana
        if (medianWidth > 20) {
            val mainLines = block.lines.filter { line ->
                val w = line.boundingBox?.rectWidth ?: return@filter true
                w >= (medianWidth * 0.32f)
            }
            if (mainLines.isNotEmpty() && mainLines.size < block.lines.size) {
                val newText = mainLines.joinToString("\n") { it.text }
                return block.copy(text = newText, lines = mainLines)
            }
        }

        return block
    }
}
