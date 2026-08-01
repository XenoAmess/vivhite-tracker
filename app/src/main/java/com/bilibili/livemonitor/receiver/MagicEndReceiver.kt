package com.bilibili.livemonitor.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.MainActivity
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.domain.MagicPeriodDecider
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

        // 响铃：复用开播提醒同一铃声源与音频属性（闹钟流，静音/勿扰下照响）
        playAlertSound(context, prefs)

        // 通知
        sendNotification(context)

        // 自排下一个未来结束
        rescheduleNext(context, prefs)
    }

    private fun playAlertSound(context: Context, prefs: PreferenceManager) {
        try {
            val player = playerFactory(context)
            val attrs = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_ALARM)
                .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                .build()
            player.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            if (!alertSoundProvider.setupDataSource(context, player, prefs.getAlertSoundUri())) {
                AppLogger.w(TAG, "all sound sources failed, skip magic alert sound")
                player.release()
                return
            }
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.playWhenReady = true
            AppLogger.d(TAG, "magic alert sound playing")
            // 10 秒后自动停（与开播提醒一致）
            android.os.Handler(context.mainLooper).postDelayed({
                try {
                    player.stop()
                    player.release()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "stop magic alert sound failed", e)
                }
            }, 10_000L)
        } catch (e: Exception) {
            AppLogger.e(TAG, "play magic alert sound failed", e)
        }
    }

    private fun sendNotification(context: Context) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, LiveMonitorApp.CHANNEL_MAGIC_ID)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle("魔法期结束！")
            .setContentText("白绮的魔法期已结束，快去看看她开播了没")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
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

        // internal 便于测试注入 fake
        internal var playerFactory: (Context) -> ExoPlayer = { context ->
            ExoPlayer.Builder(context).build()
        }
        internal var alertSoundProvider: com.bilibili.livemonitor.util.AlertSoundProvider =
            com.bilibili.livemonitor.util.AlertSoundProvider()
    }
}
