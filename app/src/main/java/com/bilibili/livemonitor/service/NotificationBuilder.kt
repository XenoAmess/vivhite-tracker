package com.bilibili.livemonitor.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.bilibili.livemonitor.AlertActivity
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.MainActivity
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.api.BilibiliActivityApi

/**
 * 通知构建与发送（从 LiveCheckService 拆出的纯机械层）。
 * 业务决策（何时发/发不发/发什么内容由谁算）仍在服务里；
 * 这里只负责把给定的数据渲染成通知并 post。
 */
class NotificationBuilder(
    private val context: Context,
    private val roomId: Long,
    private val videoIntent: (aid: Long) -> Intent,
    private val dynamicIntent: (dynamicId: String) -> Intent
) {

    fun sendTitleChange(title: String) {
        notify(
            LiveMonitorApp.NOTIFICATION_ID_TITLE_CHANGE,
            NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_STREAM_LIFECYCLE_ID)
                .setSmallIcon(R.drawable.img_on)
                .setContentTitle("白绮直播标题已改")
                .setContentText("「$title」")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent(0))
                .build()
        )
    }

    fun sendStreamEnd(durationMs: Long) {
        notify(
            LiveMonitorApp.NOTIFICATION_ID_STREAM_END,
            NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_STREAM_LIFECYCLE_ID)
                .setSmallIcon(R.drawable.img_off)
                .setContentTitle("白绮已下播")
                .setContentText("本场直播 ${formatDuration(durationMs)}")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent(0))
                .build()
        )
    }

    fun sendLiveReminder(rcmd: BilibiliActivityApi.LiveRcmdInfo) {
        val startMs = rcmd.liveStartMs ?: return
        val timeText = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(startMs))
        val title = rcmd.title?.takeIf { it.isNotBlank() } ?: "白绮直播预告"
        val contentText = rcmd.contentText?.takeIf { it.isNotBlank() } ?: "预计 $timeText 开播"
        notify(
            rcmd.dynamicId.hashCode(),
            NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_STREAM_LIFECYCLE_ID)
                .setSmallIcon(R.drawable.img_on)
                .setContentTitle("🔔 $title")
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent(0))
                .build()
        )
    }

    fun sendText(channelId: String, title: String, text: String?, silent: Boolean = false) {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle(title)
            .setContentIntent(mainActivityPendingIntent(title.hashCode()))
            .setAutoCancel(true)
        if (silent) builder.setSilent(true)
        if (!text.isNullOrBlank()) builder.setContentText(text.take(50))
        notify(title.hashCode(), builder.build())
    }

    fun sendVideo(aid: Long, title: String, prefix: String, silent: Boolean = false) {
        val builder = NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_VIDEO_ALERT_ID)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle("白绮 $prefix")
            .setContentText(title.take(50))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(videoIntent(aid), aid.toInt()))
        if (silent) builder.setSilent(true)
        notify(
            LiveMonitorApp.NOTIFICATION_ID_VIDEO,
            builder.build()
        )
    }

    fun sendDynamic(dynamicId: String, displayText: String, silent: Boolean = false) {
        val text = displayText.takeIf { it.isNotBlank() } ?: "白绮发布了新动态"
        val builder = NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_DYNAMIC_ALERT_ID)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle("白绮新动态")
            .setContentText(text.take(50))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(dynamicIntent(dynamicId), dynamicId.hashCode()))
        if (silent) builder.setSilent(true)
        notify(
            LiveMonitorApp.NOTIFICATION_ID_DYNAMIC,
            builder.build()
        )
    }

    // 勿扰时段内的静音开播通知：无 fullScreenIntent、setSilent 覆盖通道 HIGH 的默认声音
    fun sendSilentAlert() {
        val builder = NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle("🎉 白绮开播啦！")
            .setContentText("直播间 $roomId 正在直播中（勿扰时段已静音）")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(mainActivityPendingIntent(0))
        addLiveAlertActions(builder)
        notify(
            LiveMonitorApp.NOTIFICATION_ID_ALERT,
            builder.build()
        )
    }

    fun sendAlert() {
        val fullScreenIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_ALERT_ID)
                .setSmallIcon(R.drawable.img_on)
                .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.img_on))
                .setContentTitle("🎉 白绮开播啦！")
                .setContentText("直播间 $roomId 正在直播中，快去看看吧！")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent(0))
                .setFullScreenIntent(fullScreenIntent, true)
        addLiveAlertActions(builder)
        notify(LiveMonitorApp.NOTIFICATION_ID_ALERT, builder.build())
    }

    /** 构建前台服务常驻通知（startForeground 用，不 post） */
    fun buildServiceNotification(
        isLive: Boolean,
        lastCheckTime: Long,
        lastCheckSuccess: Boolean
    ): android.app.Notification {
        val iconRes = if (isLive) R.drawable.img_on else R.drawable.img_off
        val statusText = if (isLive) "🔴 直播中" else "⚫ 未开播"
        val timeText = if (lastCheckTime > 0) {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastCheckTime))
        } else null
        val contentText = when {
            !lastCheckSuccess && lastCheckTime > 0 ->
                if (timeText != null) "监控异常 · $timeText" else "监控异常"
            isLive ->
                if (timeText != null) "白绮正在直播，快去观看吧！ · $timeText"
                else "白绮正在直播，快去观看吧！"
            else ->
                if (timeText != null) "上次检测 $timeText · 未开播"
                else "正在监控直播间状态..."
        }
        return NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_SERVICE_ID)
            .setSmallIcon(iconRes)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, iconRes))
            .setContentTitle("牢白播了吗 - $statusText")
            .setContentText(contentText)
            .setContentIntent(mainActivityPendingIntent(0))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setWhen(lastCheckTime.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .build()
    }

    /** 状态变化时刷新前台常驻通知 */
    fun postServiceNotification(isLive: Boolean, lastCheckTime: Long, lastCheckSuccess: Boolean) {
        notify(
            LiveMonitorApp.NOTIFICATION_ID_SERVICE,
            buildServiceNotification(isLive, lastCheckTime, lastCheckSuccess)
        )
    }

    fun sendQuietMissedSummary(missedTs: Long, title: String) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(missedTs))
        val text = if (title.isBlank()) {
            "勿扰时段内白绮 $time 开播了"
        } else {
            "勿扰时段内白绮 $time 开播了：「$title」"
        }
        sendText(LiveMonitorApp.CHANNEL_ALERT_ID, "🔔 白绮勿扰时段内开播了", text)
    }

    private fun mainActivityPendingIntent(requestCode: Int): PendingIntent =
        pendingIntent(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            requestCode
        )

    private fun pendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun addLiveAlertActions(builder: NotificationCompat.Builder) {
        builder.addAction(
            R.drawable.img_on,
            "观看直播",
            PendingIntent.getActivity(
                context,
                10,
                Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_WATCH_LIVE
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        builder.addAction(
            R.drawable.img_off,
            "停止声音",
            serviceActionPendingIntent(LiveCheckService.ACTION_STOP_ALERT, 11)
        )
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, LiveCheckService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notify(id: Int, notification: android.app.Notification) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, notification)
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }
}
