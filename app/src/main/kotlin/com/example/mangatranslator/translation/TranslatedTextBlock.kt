package com.example.mangatranslator.translation

import android.graphics.Rect
import com.example.mangatranslator.ocr.RecognizedTextBlock

/**
 * Represents a recognized manga speech bubble that has been translated.
 *
 * @property originalBlock The raw OCR text block with coordinates.
 * @property originalText The original Japanese / Chinese / Korean text.
 * @property translatedText The translated English text.
 * @property boundingBox The bounding box of the speech bubble on screen.
 * @property isVertical Whether the original text was oriented vertically.
 * @property isShowingOriginal Whether the overlay is currently showing the original or translated text.
 */
data class TranslatedTextBlock(
    val originalBlock: RecognizedTextBlock,
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect,
    val isVertical: Boolean = false,
    var isShowingOriginal: Boolean = false
)

/**
 * Result of the full OCR + Translation pipeline for a screen frame.
 */
data class MangaTranslationResult(
    val blocks: List<TranslatedTextBlock>,
    val executionTimeMs: Long,
    val ocrTimeMs: Long,
    val translationTimeMs: Long,
    val sourceLanguage: String,
    val targetLanguage: String
) {
    val totalOriginalText: String
        get() = blocks.joinToString("\n\n") { it.originalText }

    val totalTranslatedText: String
        get() = blocks.joinToString("\n\n") { it.translatedText }

    val isEmpty: Boolean
        get() = blocks.isEmpty()
}
