package com.example.mangatranslator.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Image enhancement preprocessor tailored for Manga and Webtoon OCR.
 *
 * Boosts contrast, converts color/halftone screentones into crisp grayscale,
 * and sharpens dialogue characters so ML Kit detects small and faint characters reliably.
 */
object MangaImagePreprocessor {

    /**
     * Produces a high-contrast grayscale bitmap optimized for ML Kit text recognition.
     * The returned bitmap must be recycled when processing completes.
     */
    fun enhanceForOcr(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Convert to grayscale
        val cm = ColorMatrix()
        cm.setSaturation(0f)

        // Apply contrast stretch (1.30x) and slight brightness adjustment
        // This pushes grey halftone dots toward white and deepens black text strokes
        val contrast = 1.30f
        val brightness = -12f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return enhanced
    }
}
