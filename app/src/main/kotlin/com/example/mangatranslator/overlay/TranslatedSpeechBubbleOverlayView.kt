package com.example.mangatranslator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.example.mangatranslator.translation.TranslatedTextBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Custom View that overlays translated manga speech bubbles directly on the screen.
 * Replaces original Japanese/Chinese/Korean text with clean, wrapped, auto-sized English text.
 * Automatically expands narrow vertical boxes and scales typography dynamically using SP/DPI.
 */
class TranslatedSpeechBubbleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var translatedBlocks: List<TranslatedTextBlock> = emptyList()
    var isShowingOriginal: Boolean = false
        private set

    var fontScale: FontScale = FontScale.NORMAL
        set(value) {
            field = value
            invalidate()
        }

    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private val density = context.resources.displayMetrics.density
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels

    // Styling paints
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // Solid opaque white manga bubble cover
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000 // Subtle dark shadow for depth over manga art
        style = Paint.Style.FILL
    }

    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141414.toInt() // Solid dark manga ink border
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
    }

    private val originalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1976D2.toInt() // Blue border when showing raw original text
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6200EE.toInt()
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private val rectF = RectF()
    private val shadowRectF = RectF()

    fun setTranslatedBlocks(
        blocks: List<TranslatedTextBlock>,
        showOriginal: Boolean = false,
        scale: FontScale = fontScale
    ) {
        this.translatedBlocks = blocks
        this.isShowingOriginal = showOriginal
        this.fontScale = scale
        invalidate()
    }

    fun toggleDisplayMode(): Boolean {
        isShowingOriginal = !isShowingOriginal
        invalidate()
        return isShowingOriginal
    }

    fun clear() {
        this.translatedBlocks = emptyList()
        invalidate()
    }

    val hasTranslations: Boolean
        get() = translatedBlocks.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (translatedBlocks.isEmpty()) return

        for (block in translatedBlocks) {
            val originalBox = block.boundingBox
            if (originalBox.width() < 16 || originalBox.height() < 16) continue

            val text = if (isShowingOriginal) block.originalText else block.translatedText
            if (text.isBlank()) continue

            // 1. Smart horizontal expansion:
            // Japanese text is vertical and narrow. Horizontal English dialogue needs width to wrap naturally.
            val minComfortableWidth = when {
                text.length > 50 -> 260f * density
                text.length > 25 -> 200f * density
                text.length > 10 -> 150f * density
                else -> 100f * density
            }
            val desiredWidth = max(originalBox.width().toFloat() * 1.35f, minComfortableWidth)
            val extraWidth = (desiredWidth - originalBox.width()).coerceAtLeast(0f)

            var bubbleLeft = (originalBox.left - extraWidth / 2f).coerceAtLeast(16f * density)
            var bubbleRight = bubbleLeft + desiredWidth
            if (bubbleRight > screenWidth - 16f * density) {
                bubbleRight = screenWidth - 16f * density
                bubbleLeft = (bubbleRight - desiredWidth).coerceAtLeast(16f * density)
            }

            val paddingX = 10f * density
            val paddingY = 8f * density
            val availWidth = (bubbleRight - bubbleLeft - paddingX * 2).toInt().coerceAtLeast(20)

            // 2. High-legibility font sizing using SP with guaranteed minimum readable threshold
            val minFontSizePx = 15f * scaledDensity * fontScale.multiplier
            val maxFontSizePx = 28f * scaledDensity * fontScale.multiplier
            val baseAvailHeight = (originalBox.height() - paddingY * 2).toInt().coerceAtLeast(20)

            val layout = createFittedLayout(text, availWidth, baseAvailHeight, minFontSizePx, maxFontSizePx)

            // 3. Dynamic height growth:
            // If the readable text needs more vertical space than the box, expand outward from center
            val neededHeight = layout.height + paddingY * 2
            val finalHeight = max(originalBox.height().toFloat(), neededHeight)
            val extraHeight = (finalHeight - originalBox.height()).coerceAtLeast(0f)

            var bubbleTop = (originalBox.top - extraHeight / 2f).coerceAtLeast(16f * density)
            var bubbleBottom = bubbleTop + finalHeight
            if (bubbleBottom > screenHeight - 16f * density) {
                bubbleBottom = screenHeight - 16f * density
                bubbleTop = (bubbleBottom - finalHeight).coerceAtLeast(16f * density)
            }

            rectF.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
            val cornerRadius = min(22f * density, min(rectF.width(), rectF.height()) * 0.28f)

            // Draw subtle shadow for depth
            shadowRectF.set(rectF.left + 2f * density, rectF.top + 3f * density, rectF.right + 2f * density, rectF.bottom + 3f * density)
            canvas.drawRoundRect(shadowRectF, cornerRadius, cornerRadius, shadowPaint)

            // Draw clean white speech bubble cover
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bubbleBgPaint)
            val border = if (isShowingOriginal) originalBorderPaint else bubbleBorderPaint
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, border)

            // Center and draw wrapped text inside speech bubble
            val textX = bubbleLeft + paddingX + (availWidth - layout.width) / 2f
            val textY = bubbleTop + paddingY + ((bubbleBottom - bubbleTop - paddingY * 2) - layout.height) / 2f

            canvas.save()
            canvas.translate(textX, textY)
            layout.draw(canvas)
            canvas.restore()

            // If displaying raw original text or vertical text, show indicator tag
            if (isShowingOriginal || block.isVertical) {
                val tagText = if (isShowingOriginal) "ORIGINAL" else "V"
                val tagWidth = badgeTextPaint.measureText(tagText) + 14f * density
                val tagHeight = 18f * density
                val tagLeft = bubbleRight - tagWidth
                val tagTop = (bubbleTop - tagHeight - 2f * density).coerceAtLeast(0f)

                rectF.set(tagLeft, tagTop, bubbleRight, tagTop + tagHeight)
                canvas.drawRoundRect(rectF, 6f * density, 6f * density, badgeBgPaint)
                canvas.drawText(tagText, tagLeft + 7f * density, tagTop + 14f * density, badgeTextPaint)
            }
        }
    }

    /**
     * Finds the optimal legible font size (between [minPx] and [maxPx]) using binary search.
     * Guaranteed never to drop below [minPx] so text is always easy to read.
     */
    private fun createFittedLayout(
        text: String,
        availWidth: Int,
        availHeight: Int,
        minPx: Float,
        maxPx: Float
    ): StaticLayout {
        var low = minPx
        var high = maxPx
        var bestSize = low
        var bestLayout: StaticLayout? = null

        for (i in 0 until 5) {
            val mid = (low + high) / 2f
            textPaint.textSize = mid
            val layout = buildStaticLayout(text, availWidth)

            if (layout.height <= availHeight) {
                bestSize = mid
                bestLayout = layout
                low = mid + 1f
            } else {
                high = mid - 1f
            }
        }

        if (bestLayout == null) {
            textPaint.textSize = bestSize
            bestLayout = buildStaticLayout(text, availWidth)
        }

        return bestLayout
    }

    private fun buildStaticLayout(text: String, width: Int): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.1f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, width, Layout.Alignment.ALIGN_CENTER, 1.1f, 0f, false)
        }
    }
}
