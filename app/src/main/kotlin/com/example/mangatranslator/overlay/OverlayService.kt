package com.example.mangatranslator.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.mangatranslator.MainActivity
import com.example.mangatranslator.R
import com.example.mangatranslator.capture.ScreenCaptureManager
import com.example.mangatranslator.ocr.MangaOcrEngine
import com.example.mangatranslator.ocr.OcrLanguage
import com.example.mangatranslator.ocr.OcrResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

class OverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var windowManager: WindowManager? = null
    private var floatingBubbleView: View? = null
    private var translationOverlayView: View? = null
    private var boundingBoxOverlayView: BoundingBoxOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var screenCaptureManager: ScreenCaptureManager? = null
    private val mangaOcrEngine = MangaOcrEngine(OcrLanguage.JAPANESE)
    private var hasMediaProjectionPermission = false

    companion object {
        const val ACTION_START = "com.example.mangatranslator.START_OVERLAY"
        const val ACTION_STOP = "com.example.mangatranslator.STOP_OVERLAY"
        const val ACTION_TOGGLE_AUTO_MODE = "com.example.mangatranslator.TOGGLE_AUTO_MODE"
        const val ACTION_SET_OCR_LANGUAGE = "com.example.mangatranslator.SET_OCR_LANGUAGE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_OCR_LANGUAGE = "extra_ocr_language"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "manga_translator_overlay_channel"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isAutoModeActive = MutableStateFlow(false)
        val isAutoModeActive: StateFlow<Boolean> = _isAutoModeActive.asStateFlow()

        private val _currentOcrLanguage = MutableStateFlow(OcrLanguage.JAPANESE)
        val currentOcrLanguage: StateFlow<OcrLanguage> = _currentOcrLanguage.asStateFlow()

        private val _latestOcrResult = MutableStateFlow<OcrResult?>(null)
        val latestOcrResult: StateFlow<OcrResult?> = _latestOcrResult.asStateFlow()

        fun start(context: Context, resultCode: Int? = null, resultData: Intent? = null) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
                resultCode?.let { putExtra(EXTRA_RESULT_CODE, it) }
                resultData?.let { putExtra(EXTRA_RESULT_DATA, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun toggleAutoMode(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_TOGGLE_AUTO_MODE
            }
            context.startService(intent)
        }

        fun setLanguage(context: Context, language: OcrLanguage) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SET_OCR_LANGUAGE
                putExtra(EXTRA_OCR_LANGUAGE, language.name)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        createNotificationChannel()
        startInForeground(hasProjection = false)
        setupFloatingBubble()
        setupTranslationOverlay()
    }

    private fun startInForeground(hasProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (hasProjection) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_AUTO_MODE -> {
                toggleAutoModeInternal()
            }
            ACTION_SET_OCR_LANGUAGE -> {
                val langName = intent.getStringExtra(EXTRA_OCR_LANGUAGE)
                if (langName != null) {
                    try {
                        val language = OcrLanguage.valueOf(langName)
                        _currentOcrLanguage.value = language
                        mangaOcrEngine.setLanguage(language)
                        Toast.makeText(this, "OCR Language set to $language", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    // Elevate foreground service type to include mediaProjection now that user granted consent
                    hasMediaProjectionPermission = true
                    startInForeground(hasProjection = true)
                    initializeScreenCapture(resultCode, resultData)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        _isAutoModeActive.value = false
        hasMediaProjectionPermission = false
        serviceScope.cancel()
        screenCaptureManager?.release()
        screenCaptureManager = null
        mangaOcrEngine.close()
        removeFloatingBubble()
        removeTranslationOverlay()
    }

    private fun initializeScreenCapture(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData) ?: return

        screenCaptureManager?.release()
        screenCaptureManager = ScreenCaptureManager(this, projection, serviceScope)

        if (_isAutoModeActive.value) {
            startAutoReading()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Manga Translator Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the translation floating bubble active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }.let {
            PendingIntent.getService(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Translator Overlay")
            .setContentText("OCR & Screen capture active. Tap bubble to scan.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_translate, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)
        floatingBubbleView = bubbleView

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        layoutParams = params

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val iconView = bubbleView.findViewById<ImageView>(R.id.iv_bubble_icon)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()
                    iconView.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    iconView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    if (!isDragging) {
                        val duration = System.currentTimeMillis() - downTime
                        if (duration > 600) {
                            // Long-press: toggle Auto-reading mode
                            toggleAutoModeInternal()
                        } else {
                            // Single-tap: manual single-shot capture & OCR
                            triggerManualCapture()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    iconView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubbleView, params)
    }

    private fun setupTranslationOverlay() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val overlayView = inflater.inflate(R.layout.layout_translation_overlay, null)
        translationOverlayView = overlayView

        val container = overlayView.findViewById<FrameLayout>(R.id.translation_bubbles_container)
        val boxOverlay = BoundingBoxOverlayView(this)
        boundingBoxOverlayView = boxOverlay
        container.addView(boxOverlay)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(overlayView, overlayParams)
    }

    private fun triggerManualCapture() {
        val manager = screenCaptureManager
        if (manager == null) {
            Toast.makeText(this, "Screen capture permission needed. Open MangaTranslator to grant.", Toast.LENGTH_SHORT).show()
            return
        }

        // Hide overlay and bubble briefly to avoid capturing our own UI elements
        floatingBubbleView?.visibility = View.INVISIBLE
        clearTranslationBubbles()

        serviceScope.launch {
            delay(50) // Allow 1 frame for bubble to hide
            val bitmap = manager.captureLatestBitmap()
            floatingBubbleView?.visibility = View.VISIBLE

            if (bitmap != null) {
                processOcrOnBitmap(bitmap, isAuto = false)
            } else {
                Toast.makeText(this@OverlayService, "Screen capture failed, please retry.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleAutoModeInternal() {
        val newStatus = !_isAutoModeActive.value
        _isAutoModeActive.value = newStatus

        val badge = floatingBubbleView?.findViewById<View>(R.id.view_auto_badge)
        badge?.visibility = if (newStatus) View.VISIBLE else View.GONE

        if (newStatus) {
            Toast.makeText(this, "Auto-reading mode ON: Turning pages will translate automatically!", Toast.LENGTH_SHORT).show()
            startAutoReading()
        } else {
            Toast.makeText(this, "Auto-reading mode OFF: Manual tap mode.", Toast.LENGTH_SHORT).show()
            screenCaptureManager?.stopAutoCapture()
            clearTranslationBubbles()
        }
    }

    private fun startAutoReading() {
        val manager = screenCaptureManager ?: return
        manager.startAutoCapture(
            onPageChanging = {
                // Page is turning/scrolling -> clear old translations immediately
                clearTranslationBubbles()
                showStatusBadge("Turning page...")
            },
            onPageSettled = { newPageBitmap ->
                showStatusBadge("Page settled! Reading...")
                processOcrOnBitmap(newPageBitmap, isAuto = true)
            }
        )
    }

    private fun processOcrOnBitmap(bitmap: Bitmap, isAuto: Boolean) {
        serviceScope.launch {
            try {
                val modeTag = if (isAuto) "[Auto]" else "[Manual]"
                showStatusBadge("$modeTag Running Manga OCR...")

                val result = mangaOcrEngine.processImage(bitmap)
                _latestOcrResult.value = result

                // Render detected speech bubbles and bounding boxes
                boundingBoxOverlayView?.setBlocks(result.blocks)

                val blockCount = result.blocks.size
                val verticalCount = result.blocks.count { it.isVertical }
                val statusMsg = if (blockCount == 0) {
                    "$modeTag No text detected (${result.executionTimeMs}ms)"
                } else {
                    "$modeTag Found $blockCount blocks ($verticalCount vertical) in ${result.executionTimeMs}ms"
                }

                showStatusBadge(statusMsg)

                // Auto-hide status badge after 3 seconds
                delay(3000)
                translationOverlayView?.findViewById<View>(R.id.tv_capture_status)?.visibility = View.GONE
            } catch (e: Throwable) {
                // Safeguard against unhandled OCR / UI errors
            } finally {
                bitmap.recycle() // Recycle large screen bitmap to avoid OOM
            }
        }
    }

    private fun clearTranslationBubbles() {
        boundingBoxOverlayView?.clear()
    }

    private fun showStatusBadge(text: String) {
        val tvStatus = translationOverlayView?.findViewById<TextView>(R.id.tv_capture_status) ?: return
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    private fun removeFloatingBubble() {
        floatingBubbleView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // View might already be detached
            }
            floatingBubbleView = null
        }
    }

    private fun removeTranslationOverlay() {
        translationOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // View might already be detached
            }
            translationOverlayView = null
            boundingBoxOverlayView = null
        }
    }
}
