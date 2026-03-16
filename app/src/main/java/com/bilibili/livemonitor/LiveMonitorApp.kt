package com.bilibili.livemonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class LiveMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_SERVICE_ID,
                    "后台监控服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持应用在后台运行以监控直播间状态"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_ALERT_ID,
                    "开播提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "直播间开播时发送提醒"
                    enableVibration(true)
                    enableLights(true)
                }
            )

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannels(channels)
        }
    }

    companion object {
        const val CHANNEL_SERVICE_ID = "live_monitor_service"
        const val CHANNEL_ALERT_ID = "live_alert"
        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_ALERT = 1002
    }
}
