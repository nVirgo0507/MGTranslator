package com.example.mangatranslator.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val scope: CoroutineScope
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920
    private var screenDensity: Int = DisplayMetrics.DENSITY_DEFAULT

    private var autoDetectJob: Job? = null
    private var isAutoModeRunning = false

    // Downsampled thumbnail resolution for lightweight frame difference checking
    private val sampleWidth = 64
    private val sampleHeight = 64
    private var lastSamplePixels: IntArray? = null
    private var stableFrameCount = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            release()
        }
    }

    init {
        // Android 14+ strictly requires a registered Callback before starting capture / virtual display
        mediaProjection.registerCallback(projectionCallback, mainHandler)
        determineScreenDimensions()
        setupVirtualDisplay()
    }

    private fun determineScreenDimensions() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            screenDensity = context.resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
    }

    @SuppressLint("WrongConstant")
    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "MangaScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    /**
     * Grabs a high-resolution Bitmap of the latest screen frame.
     */
    fun captureLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val rawBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)

            return if (rowPadding == 0) {
                rawBitmap
            } else {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                rawBitmap.recycle()
                cropped
            }
        } catch (e: Throwable) {
            return null
        } finally {
            image?.close()
        }
    }

    /**
     * Starts continuous auto-capture for seamless real-time manga reading.
     * When a page turn settles, [onPageSettled] is invoked with the new screen Bitmap.
     * When a page is in motion, [onPageChanging] is invoked to hide previous translations.
     */
    fun startAutoCapture(
        onPageChanging: () -> Unit,
        onPageSettled: (Bitmap) -> Unit
    ) {
        if (isAutoModeRunning) return
        isAutoModeRunning = true
        lastSamplePixels = null
        stableFrameCount = 0
        var isPageTurnActive = false

        autoDetectJob = scope.launch(Dispatchers.Default) {
            while (isActive && isAutoModeRunning) {
                delay(350) // Sample interval: ~3 times a second (very light CPU footprint)

                val fullBitmap = captureLatestBitmap() ?: continue
                val sampleBitmap = try {
                    Bitmap.createScaledBitmap(
                        fullBitmap,
                        sampleWidth,
                        sampleHeight,
                        false
                    )
                } catch (e: Throwable) {
                    fullBitmap.recycle()
                    continue
                }

                val currentPixels = IntArray(sampleWidth * sampleHeight)
                sampleBitmap.getPixels(currentPixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
                sampleBitmap.recycle()

                val previous = lastSamplePixels
                if (previous == null) {
                    lastSamplePixels = currentPixels
                    fullBitmap.recycle()
                    continue
                }

                val diffRatio = computeFrameDifferenceRatio(previous, currentPixels)

                if (diffRatio > 0.08f) { // More than 8% of screen changed -> page is turning/scrolling
                    isPageTurnActive = true
                    stableFrameCount = 0
                    lastSamplePixels = currentPixels
                    scope.launch(Dispatchers.Main) {
                        onPageChanging()
                    }
                    fullBitmap.recycle()
                } else {
                    // Frame is visually stable
                    stableFrameCount++
                    // Settle condition: Screen was actively turning and has now remained static for 2 checks (~700ms)
                    if (isPageTurnActive && stableFrameCount >= 2) {
                        isPageTurnActive = false
                        stableFrameCount = 0
                        lastSamplePixels = currentPixels
                        scope.launch(Dispatchers.Main) {
                            onPageSettled(fullBitmap)
                        }
                    } else {
                        fullBitmap.recycle()
                    }
                }
            }
        }
    }

    /**
     * Stops continuous auto-capture.
     */
    fun stopAutoCapture() {
        isAutoModeRunning = false
        autoDetectJob?.cancel()
        autoDetectJob = null
        lastSamplePixels = null
        stableFrameCount = 0
    }

    private fun computeFrameDifferenceRatio(prev: IntArray, curr: IntArray): Float {
        var diffCount = 0
        val total = prev.size
        for (i in 0 until total) {
            val p = prev[i]
            val c = curr[i]

            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF

            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF

            val delta = abs(pr - cr) + abs(pg - cg) + abs(pb - cb)
            if (delta > 40) {
                diffCount++
            }
        }
        return diffCount.toFloat() / total
    }

    private var isReleased = false

    fun release() {
        if (isReleased) return
        isReleased = true
        stopAutoCapture()
        try {
            virtualDisplay?.release()
        } catch (e: Throwable) {
            // Ignore
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (e: Throwable) {
            // Ignore
        }
        imageReader = null
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (e: Throwable) {
            // Ignore
        }
        try {
            mediaProjection.stop()
        } catch (e: Throwable) {
            // Ignore
        }
    }
}
