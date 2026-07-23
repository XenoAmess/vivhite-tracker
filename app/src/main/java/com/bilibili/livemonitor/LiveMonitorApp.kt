package com.bilibili.livemonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.worker.LiveCheckWorker

class LiveMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        createNotificationChannels()

        // 如果监控应该在运行，注册WorkManager兜底任务
        // 服务自己的onCreate也会注册，这里是保险（如服务从未启动但偏好标记为运行）
        if (PreferenceManager(this).isServiceRunning()) {
            LiveCheckWorker.schedulePeriodic(this)
        }
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
