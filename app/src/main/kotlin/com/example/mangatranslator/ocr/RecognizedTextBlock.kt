package com.example.mangatranslator.ocr

import android.graphics.Point
import android.graphics.Rect

/**
 * Represents a recognized block of manga text along with its spatial location.
 *
 * @property text The combined recognized text.
 * @property boundingBox The bounding rectangle in screen pixels where the speech bubble / text resides.
 * @property cornerPoints Corner points of the text block for handling angled text.
 * @property lines Individual recognized lines within this block.
 * @property isVertical Whether this block is formatted as traditional vertical (top-to-bottom) manga text.
 */
data class RecognizedTextBlock(
    val text: String,
    val boundingBox: Rect,
    val cornerPoints: List<Point> = emptyList(),
    val lines: List<RecognizedLine> = emptyList(),
    val isVertical: Boolean = false
)

data class RecognizedLine(
    val text: String,
    val boundingBox: Rect?,
    val elements: List<RecognizedElement> = emptyList()
)

data class RecognizedElement(
    val text: String,
    val boundingBox: Rect?
)

/**
 * Result of processing a screen frame through the OCR pipeline.
 */
data class OcrResult(
    val blocks: List<RecognizedTextBlock>,
    val executionTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int
) {
    val totalText: String
        get() = blocks.joinToString("\n\n") { it.text }

    val isEmpty: Boolean
        get() = blocks.isEmpty()
}
