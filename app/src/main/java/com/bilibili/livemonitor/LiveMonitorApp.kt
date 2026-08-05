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
        applyDarkMode(PreferenceManager(this).getDarkMode())
        AppLogger.init(this)
        createNotificationChannels()

        // 如果监控应该在运行，注册WorkManager兜底任务
        // 服务自己的onCreate也会注册（LiveCheckService.kt），这里是进程冷启动保险：
        // ExistingPeriodicWorkPolicy.KEEP 保证重复注册幂等，不会起两份周期任务。
        if (PreferenceManager(this).isServiceRunning()) {
            LiveCheckWorker.schedulePeriodic(this)
        }
    }

    // 深色模式：在 Activity inflate 前应用（Application.onCreate 是全局生效点）
    private fun applyDarkMode(mode: Int) {
        when (mode) {
            PreferenceManager.DARK_MODE_LIGHT ->
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                )
            PreferenceManager.DARK_MODE_DARK ->
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                )
            else ->
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                )
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // channel 重要性一旦被系统记住就改不了（老用户升级不生效），
            // video/dynamic 从 DEFAULT/LOW 升 HIGH 必须换新 channel id（v2），旧 id 删除。
            // （2026-08 用户反馈：动态通知无横幅无提示音，实为 LOW 被系统折叠）
            nm.deleteNotificationChannel("video_alert")
            nm.deleteNotificationChannel("dynamic_alert")
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
                },
                NotificationChannel(
                    CHANNEL_VIDEO_ALERT_ID,
                    "新视频提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "监控的 UP 主发布新视频/置顶变化时提醒"
                    enableVibration(true)
                    enableLights(true)
                },
                NotificationChannel(
                    CHANNEL_DYNAMIC_ALERT_ID,
                    "新动态提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "监控的 UP 主发布新动态时提醒"
                    enableVibration(true)
                    enableLights(true)
                },
                NotificationChannel(
                    CHANNEL_MAGIC_ID,
                    "魔法期结束提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "记录的魔法期到结束时间时响铃提醒"
                    enableVibration(true)
                    enableLights(true)
                },
                // 下播/开播预告 共用一个 MED 通道，避免通道爆炸
                NotificationChannel(
                    CHANNEL_STREAM_LIFECYCLE_ID,
                    "直播动态",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "下播 / 开播预告提醒"
                }
            )

            nm.createNotificationChannels(channels)
        }
    }

    companion object {
        const val CHANNEL_SERVICE_ID = "live_monitor_service"
        const val CHANNEL_ALERT_ID = "live_alert"
        // v2：channel 重要性被系统记忆，升 HIGH 必须换 id（旧 video_alert/dynamic_alert 已删除）
        const val CHANNEL_VIDEO_ALERT_ID = "video_alert_v2"
        const val CHANNEL_DYNAMIC_ALERT_ID = "dynamic_alert_v2"
        const val CHANNEL_MAGIC_ID = "magic_alert"
        const val CHANNEL_STREAM_LIFECYCLE_ID = "stream_lifecycle"
        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_ALERT = 1002
        const val NOTIFICATION_ID_VIDEO = 1003
        const val NOTIFICATION_ID_DYNAMIC = 1004
        const val NOTIFICATION_ID_MAGIC = 1005
        const val NOTIFICATION_ID_STREAM_END = 1006
        const val NOTIFICATION_ID_TITLE_CHANGE = 1007
    }
}
