package com.example.mangatranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class OcrLanguage {
    JAPANESE,
    CHINESE,
    KOREAN
}

class MangaOcrEngine(
    private var currentLanguage: OcrLanguage = OcrLanguage.JAPANESE
) {
    private var textRecognizer: TextRecognizer = createRecognizer(currentLanguage)

    private fun createRecognizer(language: OcrLanguage): TextRecognizer {
        return when (language) {
            OcrLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    fun setLanguage(language: OcrLanguage) {
        if (currentLanguage != language) {
            currentLanguage = language
            textRecognizer.close()
            textRecognizer = createRecognizer(language)
        }
    }

    val selectedLanguage: OcrLanguage
        get() = currentLanguage

    /**
     * Processes a screen bitmap and extracts text blocks, bounding boxes, and vertical layout info.
     *
     * Preprocesses the image with contrast stretching and screentone suppression, recognizes text,
     * filters non-target language noise, and clusters fragmented columns into cohesive speech bubbles.
     */
    suspend fun processImage(
        bitmap: Bitmap,
        filterByLanguage: Boolean = true
    ): OcrResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // Enhance image contrast to separate dialogue strokes from background screentones
        val enhancedBitmap = try {
            MangaImagePreprocessor.enhanceForOcr(bitmap)
        } catch (e: Exception) {
            bitmap
        }

        val inputImage = InputImage.fromBitmap(enhancedBitmap, 0)

        val mlkitText: Text = try {
            textRecognizer.process(inputImage).await()
        } catch (e: Exception) {
            return@withContext OcrResult(
                blocks = emptyList(),
                executionTimeMs = System.currentTimeMillis() - startTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        } finally {
            if (enhancedBitmap !== bitmap) {
                enhancedBitmap.recycle()
            }
        }

        val recognizedBlocks = mlkitText.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            // Filter out microscopic false-positive noise
            if (box.width() < 10 || box.height() < 10) return@mapNotNull null

            // Detect vertical text orientation typical in manga:
            val isVertical = detectVerticalLayout(block)

            val lines = block.lines.map { line ->
                val elements = line.elements.map { elem ->
                    RecognizedElement(
                        text = elem.text,
                        boundingBox = elem.boundingBox
                    )
                }
                RecognizedLine(
                    text = line.text,
                    boundingBox = line.boundingBox,
                    elements = elements
                )
            }

            // For vertical Japanese manga, lines are read Right-to-Left (RTL).
            val formattedText = if (isVertical && lines.size > 1) {
                val sortedLines = lines.sortedByDescending { it.boundingBox?.centerX() ?: 0 }
                sortedLines.joinToString("\n") { it.text }
            } else {
                block.text
            }

            // Filter out text from other languages (e.g. English UI buttons, chapter headers,
            // page numbers) when language filtering is active.
            if (filterByLanguage && !LanguageScriptDetector.matchesLanguage(formattedText, currentLanguage)) {
                return@mapNotNull null
            }

            RecognizedTextBlock(
                text = formattedText,
                boundingBox = box,
                cornerPoints = block.cornerPoints?.toList() ?: emptyList(),
                lines = lines,
                isVertical = isVertical
            )
        }

        // Cluster adjacent vertical columns into unified speech bubble units
        val clusteredBlocks = MangaDialogueClusterer.clusterDialogueBlocks(recognizedBlocks, currentLanguage)

        OcrResult(
            blocks = clusteredBlocks,
            executionTimeMs = System.currentTimeMillis() - startTime,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height
        )
    }

    /**
     * Determines whether a text block represents vertical (tategaki) script.
     */
    private fun detectVerticalLayout(block: Text.TextBlock): Boolean {
        if (block.lines.isEmpty()) return false

        val box = block.boundingBox
        // If the block itself is clearly tall and slender (height > width * 1.1)
        if (box != null && box.height() > box.width() * 1.15f) {
            return true
        }

        var verticalLineCount = 0
        for (line in block.lines) {
            val lineBox = line.boundingBox ?: continue
            // A line is vertical if its height is significantly greater than its width
            if (lineBox.height() > lineBox.width() * 1.15f) {
                verticalLineCount++
            }
        }

        return verticalLineCount >= (block.lines.size / 2.0f)
    }

    fun close() {
        textRecognizer.close()
    }
}
