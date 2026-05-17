package com.hoshiyomi.payloadtoolkit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PayloadToolkitApp : Application() {
    companion object {
        const val CHANNEL_ID = "payload_toolkit_service"
        const val CHANNEL_NAME = "Payload Operations"
        const val CHANNEL_DESC = "Background payload processing notifications"
        lateinit var instance: PayloadToolkitApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
