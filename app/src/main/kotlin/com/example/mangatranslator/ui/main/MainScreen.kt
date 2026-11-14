package com.example.mangatranslator.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.example.mangatranslator.R
import com.example.mangatranslator.ocr.OcrLanguage
import com.example.mangatranslator.overlay.OverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    // Refresh permission state on resume (e.g. after returning from Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    // Launcher for MediaProjection screen capture consent dialog
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            OverlayService.start(
                context = context,
                resultCode = result.resultCode,
                resultData = result.data
            )
        }
    }

    val isOverlayRunning by OverlayService.isServiceRunning.collectAsStateWithLifecycle()
    val isAutoModeActive by OverlayService.isAutoModeActive.collectAsStateWithLifecycle()
    val currentOcrLanguage by OverlayService.currentOcrLanguage.collectAsStateWithLifecycle()
    val latestOcrResult by OverlayService.latestOcrResult.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOverlayRunning) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (isOverlayRunning) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isOverlayRunning) "Overlay & OCR Active" else "Translator Inactive",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isOverlayRunning) {
                                    if (isAutoModeActive) {
                                        "Mode: Auto-Read (Translates on page turns) • Lang: $currentOcrLanguage"
                                    } else {
                                        "Mode: Manual Tap (Tap bubble to scan) • Lang: $currentOcrLanguage"
                                    }
                                } else {
                                    "Start service to enable floating bubble & manga OCR."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // OCR Language Selection Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Manga Script / Language",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OcrLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = currentOcrLanguage == lang,
                                onClick = {
                                    OverlayService.setLanguage(context, lang)
                                },
                                label = {
                                    Text(
                                        when (lang) {
                                            OcrLanguage.JAPANESE -> "Japanese (日本語)"
                                            OcrLanguage.CHINESE -> "Chinese (中文)"
                                            OcrLanguage.KOREAN -> "Korean (한국어)"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Live OCR Diagnostics Card (shows recognized manga text in real time)
            latestOcrResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Latest OCR Scan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${result.executionTimeMs}ms • ${result.blocks.size} blocks (${result.blocks.count { it.isVertical }} vert)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        if (result.blocks.isEmpty()) {
                            Text(
                                "No speech bubbles or text detected on the latest screen frame.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                result.blocks.take(5).forEachIndexed { index, block ->
                                    val tag = if (block.isVertical) "[Vertical] " else ""
                                    Text(
                                        text = "${index + 1}. $tag\"${block.text.replace("\n", " ")}\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                if (result.blocks.size > 5) {
                                    Text(
                                        text = "+ ${result.blocks.size - 5} more blocks...",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Permissions Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Overlay permission row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Display over other apps", fontWeight = FontWeight.Medium)
                            Text(
                                if (hasOverlayPermission) "Granted" else "Required for floating bubble & overlay",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasOverlayPermission) Color(0xFF388E3C) else MaterialTheme.colorScheme.error
                            )
                        }
                        if (!hasOverlayPermission) {
                            FilledTonalButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            ) {
                                Text("Grant")
                            }
                        }
                    }

                    // Notification permission row for Android 13+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Notifications", fontWeight = FontWeight.Medium)
                                Text(
                                    if (hasNotificationPermission) "Granted" else "Needed for background capture service",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hasNotificationPermission) Color(0xFF388E3C) else MaterialTheme.colorScheme.error
                                )
                            }
                            if (!hasNotificationPermission) {
                                FilledTonalButton(
                                    onClick = {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                ) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }
            }

            // Action Control Buttons
            if (isOverlayRunning) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Toggle Auto-Mode Button
                    OutlinedButton(
                        onClick = {
                            OverlayService.toggleAutoMode(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isAutoModeActive) "Switch to Manual Mode" else "Switch to Auto-Reading Mode",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Stop Service Button
                    Button(
                        onClick = {
                            OverlayService.stop(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Stop Floating Bubble", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (!hasOverlayPermission) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            // Request MediaProjection consent dialog
                            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_translate),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Manga Translator", style = MaterialTheme.typography.titleMedium)
                }
            }

            // Guide Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Phase 3: Manga OCR Features",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "• ML Kit On-Device Japanese, Chinese, and Korean OCR engine.\n\n" +
                        "• Vertical Manga Script Detection:\n" +
                        "   Automatically detects vertical (tategaki) text lines, tags them with [V], and reorders right-to-left lines for correct reading order.\n\n" +
                        "• Visual Bounding Boxes:\n" +
                        "   When text is scanned (manually or via auto-read on page turn), blue highlight boxes are drawn directly over each speech bubble on screen.\n\n" +
                        "• Live Scan Diagnostics:\n" +
                        "   Return to this screen at any time to see the raw detected manga text and OCR performance metrics in real time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
