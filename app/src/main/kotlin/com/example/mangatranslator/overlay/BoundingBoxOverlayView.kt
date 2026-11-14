package com.example.mangatranslator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.example.mangatranslator.ocr.RecognizedTextBlock

/**
 * Custom View that visually overlays detected manga text bounding boxes and vertical badges.
 */
class BoundingBoxOverlayView(context: Context) : View(context) {

    private var textBlocks: List<RecognizedTextBlock> = emptyList()

    private val boxPaint = Paint().apply {
        color = 0x882196F3.toInt() // Semi-transparent blue fill
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = 0xFF2196F3.toInt() // Solid blue border
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val verticalBadgePaint = Paint().apply {
        color = 0xFFE91E63.toInt() // Pink badge for vertical manga text
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 24f
        isAntiAlias = true
        isFakeBoldText = true
    }

    fun setBlocks(blocks: List<RecognizedTextBlock>) {
        this.textBlocks = blocks
        invalidate()
    }

    fun clear() {
        this.textBlocks = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (block in textBlocks) {
            val box = block.boundingBox

            // Draw filled bounding box highlight
            canvas.drawRect(box, boxPaint)
            canvas.drawRect(box, borderPaint)

            // If vertical manga text, draw a small [V] indicator tag at the top right of the box
            if (block.isVertical) {
                val tagWidth = 36f
                val tagHeight = 32f
                val tagLeft = box.right - tagWidth
                val tagTop = box.top.toFloat()

                canvas.drawRect(tagLeft, tagTop, box.right.toFloat(), tagTop + tagHeight, verticalBadgePaint)
                canvas.drawText("V", tagLeft + 8f, tagTop + 24f, badgeTextPaint)
            }
        }
    }
}
