package com.hoshiyomi.payloadtoolkit.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hoshiyomi.payloadtoolkit.PayloadBridge
import com.hoshiyomi.payloadtoolkit.PayloadResult
import com.hoshiyomi.payloadtoolkit.PayloadToolkitApp
import com.hoshiyomi.payloadtoolkit.ProgressUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * PayloadService — Foreground service that keeps the build process alive.
 *
 * Android can kill background coroutines (lifecycleScope) under memory pressure,
 * especially during long compression operations. This service:
 *   1. Runs as a foreground service with a persistent notification
 *   2. Holds a WakeLock to prevent CPU sleep during heavy I/O
 *   3. Executes the build via PayloadBridge.dd() and updates notification status
 *   4. Broadcasts results back to MainActivity via LocalBroadcastManager-style intent extras
 *
 * The service is started by MainActivity and stops itself when the operation completes.
 */
class PayloadService : Service() {

    companion object {
        private const val TAG = "PayloadService"
        const val NOTIFICATION_ID = 1001

        // Intent action broadcast back to MainActivity
        const val ACTION_BUILD_RESULT = "com.hoshiyomi.payloadtoolkit.ACTION_BUILD_RESULT"
        const val ACTION_BUILD_PROGRESS = "com.hoshiyomi.payloadtoolkit.ACTION_BUILD_PROGRESS"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"
        const val EXTRA_PROGRESS_MESSAGE = "progress_message"

        // Notification ID constants for updating
        private const val NOTIFICATION_TITLE = "OTAku"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var operationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground immediately with "preparing" notification
        startForegroundNotification("Preparing build operation...")

        operationJob = serviceScope.launch {
            executeBuild(intent)
        }

        return START_NOT_STICKY
    }

    // ═══════════════════════════════════════════════════════════════
    //  Core execution
    // ═══════════════════════════════════════════════════════════════

    private suspend fun executeBuild(intent: Intent?) {
        val images: Map<String, String> =
            @Suppress("DEPRECATION")
            (intent?.getSerializableExtra("images") as? Map<*, *>)?.mapKeys { it.key.toString() }
                ?.mapValues { it.value.toString() } ?: emptyMap()

        val device = intent?.getStringExtra("device") ?: "generic"
        val compression = intent?.getStringExtra("compression") ?: "gzip"
        val level = intent?.getIntExtra("level", 0) ?: 0
        val outputPath = intent?.getStringExtra("output_path") ?: ""

        if (images.isEmpty() || outputPath.isBlank()) {
            updateNotification("Build failed: missing parameters", isError = true)
            broadcastResult(PayloadResult.error("Missing images or output path"))
            stopSelf()
            return
        }

        val partitionInfo = images.keys.sorted().joinToString(", ")
        updateNotification("Building: $partitionInfo [$compression]...")

        val result = PayloadBridge.dd(
            images = images,
            device = device,
            compression = compression,
            level = level,
            outputPath = outputPath,
            onProgress = { progress ->
                // Update notification with progress percentage
                val notifText = "Building: $progress.percent% — ${progress.message}"
                updateNotification(notifText)
                // Broadcast progress to MainActivity for progress bar
                broadcastProgress(progress)
            }
        )

        // Update notification with final status
        if (result.success) {
            updateNotification(
                "Build completed in ${formatDuration(result.durationMs)}",
                isSuccess = true
            )
        } else {
            updateNotification(
                "Build failed after ${formatDuration(result.durationMs)}",
                isError = true
            )
        }

        broadcastResult(result)
        stopSelf()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Notification management
    // ═══════════════════════════════════════════════════════════════

    private fun startForegroundNotification(text: String) {
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                // Below API 34: no type required (FOREGROUND_SERVICE permission suffices)
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground type failed: ${e.message}, falling back to plain")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground plain also failed: ${e2.message}")
            }
        }
    }

    private fun updateNotification(text: String, isSuccess: Boolean = false, isError: Boolean = false) {
        val notification = buildNotification(text, isSuccess, isError)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        text: String,
        isSuccess: Boolean = false,
        isError: Boolean = false
    ): android.app.Notification {
        return NotificationCompat.Builder(this, PayloadToolkitApp.CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Non-swipeable while running
            .setSilent(true) // No sound for status updates
            .apply {
                if (isSuccess) {
                    setAutoCancel(true) // Allow dismiss on success
                }
                if (isError) {
                    setAutoCancel(true) // Allow dismiss on error
                }
            }
            .build()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Result broadcast
    // ═══════════════════════════════════════════════════════════════

    private fun broadcastResult(result: PayloadResult) {
        val broadcast = Intent(ACTION_BUILD_RESULT).apply {
            putExtra(EXTRA_SUCCESS, result.success)
            putExtra(EXTRA_OUTPUT, result.output)
            putExtra(EXTRA_ERROR, result.error)
            putExtra(EXTRA_DURATION_MS, result.durationMs)
            setPackage(packageName)
        }
        sendBroadcast(broadcast)
    }

    private fun broadcastProgress(progress: ProgressUpdate) {
        val broadcast = Intent(ACTION_BUILD_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS_PERCENT, progress.percent)
            putExtra(EXTRA_PROGRESS_MESSAGE, progress.message)
            setPackage(packageName)
        }
        sendBroadcast(broadcast)
    }

    // ═══════════════════════════════════════════════════════════════
    //  WakeLock — prevent CPU sleep during heavy I/O
    // ═══════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PayloadToolkit::BuildWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30 minute max timeout safety
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) { /* already released */ }
        wakeLock = null
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    override fun onDestroy() {
        operationJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds < 60) "${seconds}s"
        else "${seconds / 60}m ${seconds % 60}s"
    }
}
