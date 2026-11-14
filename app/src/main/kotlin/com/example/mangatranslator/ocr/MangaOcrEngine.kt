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

    /**
     * Processes a screen bitmap and extracts text blocks, bounding boxes, and vertical layout info.
     */
    suspend fun processImage(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val mlkitText: Text = try {
            textRecognizer.process(inputImage).await()
        } catch (e: Exception) {
            return@withContext OcrResult(
                blocks = emptyList(),
                executionTimeMs = System.currentTimeMillis() - startTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        }

        val recognizedBlocks = mlkitText.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            // Filter out microscopic false-positive noise
            if (box.width() < 10 || box.height() < 10) return@mapNotNull null

            // Detect vertical text orientation typical in manga:
            // In vertical layout, individual lines are taller than they are wide (height > width * 1.2),
            // or the block itself is tall and contains multiple thin vertical lines.
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
            // ML Kit might return lines in arbitrary or LTR order. If vertical, sort lines by X descending.
            val formattedText = if (isVertical && lines.size > 1) {
                val sortedLines = lines.sortedByDescending { it.boundingBox?.centerX() ?: 0 }
                sortedLines.joinToString("\n") { it.text }
            } else {
                block.text
            }

            RecognizedTextBlock(
                text = formattedText,
                boundingBox = box,
                cornerPoints = block.cornerPoints?.toList() ?: emptyList(),
                lines = lines,
                isVertical = isVertical
            )
        }

        OcrResult(
            blocks = recognizedBlocks,
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

        var verticalLineCount = 0
        for (line in block.lines) {
            val box = line.boundingBox ?: continue
            // A line is vertical if its height is significantly greater than its width
            if (box.height() > box.width() * 1.15f) {
                verticalLineCount++
            }
        }

        return verticalLineCount >= (block.lines.size / 2.0f)
    }

    fun close() {
        textRecognizer.close()
    }
}
