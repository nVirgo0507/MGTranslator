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
 */
class TranslatedSpeechBubbleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var translatedBlocks: List<TranslatedTextBlock> = emptyList()
    var isShowingOriginal: Boolean = false
        private set

    // Styling paints
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF7FFFFFF.toInt() // 97% opaque clean white manga bubble fill
        style = Paint.Style.FILL
    }

    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt() // Dark manga ink border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val originalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1976D2.toInt() // Blue border when showing raw original text
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
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
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val rectF = RectF()

    fun setTranslatedBlocks(blocks: List<TranslatedTextBlock>, showOriginal: Boolean = false) {
        this.translatedBlocks = blocks
        this.isShowingOriginal = showOriginal
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
            val box = block.boundingBox
            if (box.width() < 16 || box.height() < 16) continue

            val text = if (isShowingOriginal) block.originalText else block.translatedText
            if (text.isBlank()) continue

            // Define padded bubble bounds
            val paddingX = max(6f, box.width() * 0.08f)
            val paddingY = max(6f, box.height() * 0.08f)
            rectF.set(box)

            val cornerRadius = min(20f, min(box.width(), box.height()) * 0.25f)

            // Draw clean speech bubble cover
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bubbleBgPaint)
            val border = if (isShowingOriginal) originalBorderPaint else bubbleBorderPaint
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, border)

            // Draw fitted, centered, wrapped text inside speech bubble
            val availWidth = (box.width() - paddingX * 2).toInt().coerceAtLeast(10)
            val availHeight = (box.height() - paddingY * 2).toInt().coerceAtLeast(10)

            val layout = createFittedLayout(text, availWidth, availHeight)

            val textX = box.left + paddingX + (availWidth - layout.width) / 2f
            val textY = box.top + paddingY + (availHeight - layout.height) / 2f

            canvas.save()
            canvas.translate(textX, textY)
            layout.draw(canvas)
            canvas.restore()

            // When displaying original, show small [RAW] or [V] indicator tag
            if (isShowingOriginal || block.isVertical) {
                val tagText = if (isShowingOriginal) "ORIGINAL" else "V"
                val tagWidth = badgeTextPaint.measureText(tagText) + 12f
                val tagHeight = 24f
                val tagLeft = box.right - tagWidth
                val tagTop = (box.top - tagHeight).coerceAtLeast(0f)

                rectF.set(tagLeft, tagTop, box.right.toFloat(), tagTop + tagHeight)
                canvas.drawRoundRect(rectF, 6f, 6f, badgeBgPaint)
                canvas.drawText(tagText, tagLeft + 6f, tagTop + 18f, badgeTextPaint)
            }
        }
    }

    /**
     * Binary searches for the best font size (between 10sp and 34sp) so the translated
     * English dialogue fills the speech bubble comfortably without clipping or overflowing.
     */
    private fun createFittedLayout(text: String, availWidth: Int, availHeight: Int): StaticLayout {
        var low = 11f
        var high = min(34f, availHeight * 0.45f).coerceAtLeast(low)
        var bestSize = low
        var bestLayout: StaticLayout? = null

        // Quick binary search over 5 iterations for ideal typography
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
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, width, Layout.Alignment.ALIGN_CENTER, 1.05f, 0f, false)
        }
    }
}
