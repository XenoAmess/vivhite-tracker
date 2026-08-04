package com.bilibili.livemonitor.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.MainActivity
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.domain.MagicPeriodDecider
import com.bilibili.livemonitor.service.MagicAlertService
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.MagicAlarmScheduler
import com.bilibili.livemonitor.util.MagicPeriodStore
import com.bilibili.livemonitor.util.PreferenceManager

/**
 * 魔法期结束提醒：到点响铃（复用开播铃声链路：当前选用铃声 + USAGE_ALARM 循环 10s）
 * + 高优先级通知，然后自排下一个未来结束。
 *
 * 触发源：MagicAlarmScheduler 排的精确闹钟；记录变更/开机后重排。
 */
class MagicEndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.d(TAG, "MagicEndReceiver onReceive")
        val prefs = PreferenceManager(context)

        // Receiver 返回后系统可杀进程；响铃必须交给前台服务持有，不能靠延迟 Handler。
        val alertStarted = try {
            alertStarter(context)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "start magic alert service failed", e)
            false
        }

        // 通知
        sendNotification(context, useSystemSoundFallback = !alertStarted)

        // 自排下一个未来结束
        rescheduleNext(context, prefs)
    }

    private fun sendNotification(context: Context, useSystemSoundFallback: Boolean) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_MAGIC_ID)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle("魔法期结束！")
            .setContentText("白绮的魔法期已结束，快去看看她开播了没")
            .setPriority(if (useSystemSoundFallback) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (useSystemSoundFallback) {
            // 前台服务被系统拒绝时无法可靠持有自定义播放器，至少让系统通知通道
            // 立即使用默认声音/震动提示用户，而不是静默丢失这次提醒。
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }
        val notification = builder.build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(LiveMonitorApp.NOTIFICATION_ID_MAGIC, notification)
        AppLogger.d(TAG, "magic end notification posted")
    }

    private fun rescheduleNext(context: Context, prefs: PreferenceManager) {
        val periods = MagicPeriodStore.load(prefs)
        val next = MagicPeriodDecider.nextPendingEnd(periods, System.currentTimeMillis())
        if (next != null) {
            MagicAlarmScheduler.schedule(context, next)
        } else {
            MagicAlarmScheduler.cancel(context)
        }
    }

    companion object {
        private const val TAG = "MagicEndReceiver"

        // internal 便于测试验证 Receiver 不再自行持有播放器。
        internal var alertStarter: (Context) -> Unit = MagicAlertService::start
    }
}
