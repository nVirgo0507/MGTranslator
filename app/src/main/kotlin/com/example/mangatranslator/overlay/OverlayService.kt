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
import com.example.mangatranslator.translation.MangaTranslationEngine
import com.example.mangatranslator.translation.MangaTranslationResult
import com.example.mangatranslator.translation.TargetLanguage
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
    private var speechBubbleOverlayView: TranslatedSpeechBubbleOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var screenCaptureManager: ScreenCaptureManager? = null
    private val mangaOcrEngine = MangaOcrEngine(OcrLanguage.JAPANESE)
    private val mangaTranslationEngine = MangaTranslationEngine(OcrLanguage.JAPANESE, TargetLanguage.ENGLISH)
    private var hasMediaProjectionPermission = false

    companion object {
        const val ACTION_START = "com.example.mangatranslator.START_OVERLAY"
        const val ACTION_STOP = "com.example.mangatranslator.STOP_OVERLAY"
        const val ACTION_TOGGLE_AUTO_MODE = "com.example.mangatranslator.TOGGLE_AUTO_MODE"
        const val ACTION_SET_OCR_LANGUAGE = "com.example.mangatranslator.SET_OCR_LANGUAGE"
        const val ACTION_SET_TARGET_LANGUAGE = "com.example.mangatranslator.SET_TARGET_LANGUAGE"
        const val ACTION_SET_FONT_SCALE = "com.example.mangatranslator.SET_FONT_SCALE"
        const val ACTION_SET_FILTER_NON_SOURCE = "com.example.mangatranslator.SET_FILTER_NON_SOURCE"
        const val ACTION_TOGGLE_ORIGINAL = "com.example.mangatranslator.TOGGLE_ORIGINAL"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_OCR_LANGUAGE = "extra_ocr_language"
        const val EXTRA_TARGET_LANGUAGE = "extra_target_language"
        const val EXTRA_FONT_SCALE = "extra_font_scale"
        const val EXTRA_FILTER_NON_SOURCE = "extra_filter_non_source"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "manga_translator_overlay_channel"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isAutoModeActive = MutableStateFlow(false)
        val isAutoModeActive: StateFlow<Boolean> = _isAutoModeActive.asStateFlow()

        private val _currentOcrLanguage = MutableStateFlow(OcrLanguage.JAPANESE)
        val currentOcrLanguage: StateFlow<OcrLanguage> = _currentOcrLanguage.asStateFlow()

        private val _currentTargetLanguage = MutableStateFlow(TargetLanguage.ENGLISH)
        val currentTargetLanguage: StateFlow<TargetLanguage> = _currentTargetLanguage.asStateFlow()

        private val _fontScale = MutableStateFlow(FontScale.NORMAL)
        val fontScale: StateFlow<FontScale> = _fontScale.asStateFlow()

        private val _filterNonSourceLanguage = MutableStateFlow(true)
        val filterNonSourceLanguage: StateFlow<Boolean> = _filterNonSourceLanguage.asStateFlow()

        private val _latestOcrResult = MutableStateFlow<OcrResult?>(null)
        val latestOcrResult: StateFlow<OcrResult?> = _latestOcrResult.asStateFlow()

        private val _latestTranslationResult = MutableStateFlow<MangaTranslationResult?>(null)
        val latestTranslationResult: StateFlow<MangaTranslationResult?> = _latestTranslationResult.asStateFlow()

        private val _modelDownloadStatus = MutableStateFlow<String?>(null)
        val modelDownloadStatus: StateFlow<String?> = _modelDownloadStatus.asStateFlow()

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
            _currentOcrLanguage.value = language
            if (_isServiceRunning.value) {
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = ACTION_SET_OCR_LANGUAGE
                    putExtra(EXTRA_OCR_LANGUAGE, language.name)
                }
                context.startService(intent)
            }
        }

        fun setTargetLanguage(context: Context, language: TargetLanguage) {
            _currentTargetLanguage.value = language
            if (_isServiceRunning.value) {
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = ACTION_SET_TARGET_LANGUAGE
                    putExtra(EXTRA_TARGET_LANGUAGE, language.name)
                }
                context.startService(intent)
            }
        }

        fun setFontScale(context: Context, scale: FontScale) {
            _fontScale.value = scale
            if (_isServiceRunning.value) {
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = ACTION_SET_FONT_SCALE
                    putExtra(EXTRA_FONT_SCALE, scale.name)
                }
                context.startService(intent)
            }
        }

        fun setFilterNonSourceLanguage(context: Context, enabled: Boolean) {
            _filterNonSourceLanguage.value = enabled
            if (_isServiceRunning.value) {
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = ACTION_SET_FILTER_NON_SOURCE
                    putExtra(EXTRA_FILTER_NON_SOURCE, enabled)
                }
                context.startService(intent)
            }
        }

        fun toggleOriginalText(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_TOGGLE_ORIGINAL
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

        // Proactively warm up and download on-device translation model
        serviceScope.launch(Dispatchers.IO) {
            mangaTranslationEngine.ensureModelDownloaded { status ->
                _modelDownloadStatus.value = status
            }
        }
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
            ACTION_TOGGLE_ORIGINAL -> {
                toggleOriginalTextInternal()
            }
            ACTION_SET_FONT_SCALE -> {
                val scaleName = intent.getStringExtra(EXTRA_FONT_SCALE)
                if (scaleName != null) {
                    try {
                        val scale = FontScale.valueOf(scaleName)
                        _fontScale.value = scale
                        speechBubbleOverlayView?.fontScale = scale
                        Toast.makeText(this, "Font Size: ${scale.label}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            ACTION_SET_FILTER_NON_SOURCE -> {
                val filter = intent.getBooleanExtra(EXTRA_FILTER_NON_SOURCE, true)
                _filterNonSourceLanguage.value = filter
                val label = if (filter) "Filtering non-source text ON" else "Translating all text"
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            }
            ACTION_SET_OCR_LANGUAGE -> {
                val langName = intent.getStringExtra(EXTRA_OCR_LANGUAGE)
                if (langName != null) {
                    try {
                        val language = OcrLanguage.valueOf(langName)
                        _currentOcrLanguage.value = language
                        mangaOcrEngine.setLanguage(language)
                        mangaTranslationEngine.setLanguages(language, _currentTargetLanguage.value)
                        Toast.makeText(this, "Source Language: $language", Toast.LENGTH_SHORT).show()
                        serviceScope.launch(Dispatchers.IO) {
                            mangaTranslationEngine.ensureModelDownloaded { status ->
                                _modelDownloadStatus.value = status
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            ACTION_SET_TARGET_LANGUAGE -> {
                val targetName = intent.getStringExtra(EXTRA_TARGET_LANGUAGE)
                if (targetName != null) {
                    try {
                        val target = TargetLanguage.valueOf(targetName)
                        _currentTargetLanguage.value = target
                        mangaTranslationEngine.setLanguages(_currentOcrLanguage.value, target)
                        Toast.makeText(this, "Translate to: ${target.displayName}", Toast.LENGTH_SHORT).show()
                        serviceScope.launch(Dispatchers.IO) {
                            mangaTranslationEngine.ensureModelDownloaded { status ->
                                _modelDownloadStatus.value = status
                            }
                        }
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
        mangaTranslationEngine.close()
        removeFloatingBubble()
        removeTranslationOverlay()
    }

    private fun initializeScreenCapture(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
            .setContentText("Screen capture & on-device translation active.")
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
        var lastTapTime = 0L

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
                            // Long-press (>600ms): toggle Auto-reading mode
                            toggleAutoModeInternal()
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 350 && speechBubbleOverlayView?.hasTranslations == true) {
                                // Double-tap: Toggle between original and translated text
                                toggleOriginalTextInternal()
                            } else {
                                // Single-tap: Trigger manual scan & translation
                                triggerManualCapture()
                            }
                            lastTapTime = now
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
        val speechOverlay = TranslatedSpeechBubbleOverlayView(this).apply {
            fontScale = _fontScale.value
        }
        speechBubbleOverlayView = speechOverlay
        container.addView(speechOverlay)

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

        // Hide overlay and bubble completely before frame capture to prevent self-occlusion
        floatingBubbleView?.visibility = View.GONE
        clearTranslationBubbles()

        serviceScope.launch {
            // Delay 120ms to allow display compositor to produce clean frame without floating bubble
            delay(120)
            val bitmap = manager.captureLatestBitmap()
            floatingBubbleView?.visibility = View.VISIBLE

            if (bitmap != null) {
                processOcrAndTranslation(bitmap, isAuto = false)
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
            Toast.makeText(this, "Auto-reading mode ON: Translating pages automatically!", Toast.LENGTH_SHORT).show()
            startAutoReading()
        } else {
            Toast.makeText(this, "Manual mode: Tap floating bubble to translate.", Toast.LENGTH_SHORT).show()
            screenCaptureManager?.stopAutoCapture()
        }
    }

    private fun toggleOriginalTextInternal() {
        val overlay = speechBubbleOverlayView ?: return
        val isShowingOriginal = overlay.toggleDisplayMode()
        val label = if (isShowingOriginal) "Showing Original Manga Text" else "Showing English Translation"
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun startAutoReading() {
        val manager = screenCaptureManager ?: return
        manager.startAutoCapture(
            onPageChanging = {
                clearTranslationBubbles()
                showStatusBadge("Turning page...")
            },
            onPageSettled = { newPageBitmap ->
                showStatusBadge("Page settled! Translating...")
                processOcrAndTranslation(newPageBitmap, isAuto = true)
            }
        )
    }

    private fun processOcrAndTranslation(bitmap: Bitmap, isAuto: Boolean) {
        serviceScope.launch {
            try {
                val modeTag = if (isAuto) "[Auto]" else "[Manual]"
                val srcLang = _currentOcrLanguage.value.name
                showStatusBadge("$modeTag Scanning $srcLang manga dialogue...")

                val ocrStart = System.currentTimeMillis()
                val ocrResult = mangaOcrEngine.processImage(
                    bitmap = bitmap,
                    filterByLanguage = _filterNonSourceLanguage.value
                )
                val ocrTime = System.currentTimeMillis() - ocrStart
                _latestOcrResult.value = ocrResult

                if (ocrResult.blocks.isEmpty()) {
                    showStatusBadge("$modeTag No $srcLang dialogue detected")
                    speechBubbleOverlayView?.clear()
                    delay(2000)
                    translationOverlayView?.findViewById<View>(R.id.tv_capture_status)?.visibility = View.GONE
                    return@launch
                }

                showStatusBadge("$modeTag Translating ${ocrResult.blocks.size} $srcLang speech bubbles...")

                val transStart = System.currentTimeMillis()
                val translatedBlocks = mangaTranslationEngine.translateBlocks(ocrResult.blocks) { status ->
                    showStatusBadge(status)
                }
                val transTime = System.currentTimeMillis() - transStart

                val translationResult = MangaTranslationResult(
                    blocks = translatedBlocks,
                    executionTimeMs = ocrTime + transTime,
                    ocrTimeMs = ocrTime,
                    translationTimeMs = transTime,
                    sourceLanguage = _currentOcrLanguage.value.name,
                    targetLanguage = _currentTargetLanguage.value.displayName
                )
                _latestTranslationResult.value = translationResult

                // Render clean translated speech bubbles directly covering original dialogue
                speechBubbleOverlayView?.setTranslatedBlocks(
                    blocks = translatedBlocks,
                    showOriginal = false,
                    scale = _fontScale.value
                )

                val statusMsg = "$modeTag Translated ${translatedBlocks.size} $srcLang bubbles in ${ocrTime + transTime}ms"
                showStatusBadge(statusMsg)

                // Auto-hide status banner after 3 seconds
                delay(3000)
                translationOverlayView?.findViewById<View>(R.id.tv_capture_status)?.visibility = View.GONE
            } catch (e: Throwable) {
                // Safeguard against unhandled exceptions
            } finally {
                bitmap.recycle() // Recycle native bitmap immediately
            }
        }
    }

    private fun clearTranslationBubbles() {
        speechBubbleOverlayView?.clear()
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
            speechBubbleOverlayView = null
        }
    }
}
