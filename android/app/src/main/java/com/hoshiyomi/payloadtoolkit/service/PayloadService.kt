package com.hoshiyomi.payloadtoolkit.service

import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hoshiyomi.payloadtoolkit.PayloadToolkitApp
import kotlinx.coroutines.*

class PayloadService : Service() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_INPUT_PATH = "input_path"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val NOTIFICATION_ID = 1001

        fun createIntent(context: Context, mode: String, inputPath: String, outputPath: String): Intent {
            return Intent(context, PayloadService::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_INPUT_PATH, inputPath)
                putExtra(EXTRA_OUTPUT_PATH, outputPath)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var operationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: return START_NOT_STICKY
        val inputPath = intent.getStringExtra(EXTRA_INPUT_PATH) ?: return START_NOT_STICKY
        val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: return START_NOT_STICKY

        startForegroundNotification(mode)
        executeOperation(mode, inputPath, outputPath)

        return START_NOT_STICKY
    }

    private fun startForegroundNotification(mode: String) {
        val notification = NotificationCompat.Builder(this, PayloadToolkitApp.CHANNEL_ID)
            .setContentTitle("Payload Toolkit")
            .setContentText("Running $mode operation...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun executeOperation(mode: String, inputPath: String, outputPath: String) {
        operationJob = serviceScope.launch {
            // Delegated to PayloadBridge in future integration
            delay(1000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        operationJob?.cancel()
        serviceScope.cancel()
    }
}
