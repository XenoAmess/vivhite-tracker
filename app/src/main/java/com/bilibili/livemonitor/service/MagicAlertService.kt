package com.bilibili.livemonitor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.util.AlertSoundProvider
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager

/** Keeps the magic-period alarm alive after its BroadcastReceiver has returned. */
class MagicAlertService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val stopRunnable = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_PLAY) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID_MAGIC_PLAYBACK, foregroundNotification())
        handler.removeCallbacks(stopRunnable)
        stopPlayback()

        var nextPlayer: ExoPlayer? = null
        try {
            val createdPlayer = playerFactory(this)
            nextPlayer = createdPlayer
            val attributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_ALARM)
                .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                .build()
            createdPlayer.setAudioAttributes(attributes, /* handleAudioFocus = */ false)
            if (!alertSoundProvider.setupDataSource(this, createdPlayer, PreferenceManager(this).getAlertSoundUri())) {
                AppLogger.w(TAG, "all sound sources failed, skip magic alert sound")
                createdPlayer.release()
                nextPlayer = null
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            createdPlayer.repeatMode = Player.REPEAT_MODE_ONE
            createdPlayer.playWhenReady = true
            player = createdPlayer
            nextPlayer = null
            handler.postDelayed(stopRunnable, PLAYBACK_DURATION_MS)
        } catch (e: Exception) {
            nextPlayer?.let { failedPlayer ->
                runCatching { failedPlayer.release() }
                    .onFailure { AppLogger.w(TAG, "release magic player after failure failed", it) }
            }
            AppLogger.e(TAG, "play magic alert sound failed", e)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stopPlayback() {
        player?.let { current ->
            runCatching {
                if (current.isPlaying) current.stop()
                current.release()
            }.onFailure { AppLogger.w(TAG, "stop magic alert sound failed", it) }
        }
        player = null
    }

    private fun foregroundNotification() = NotificationCompat.Builder(this, LiveMonitorApp.CHANNEL_SERVICE_ID)
        .setSmallIcon(R.drawable.img_on)
        .setContentTitle("魔法期结束提醒")
        .setContentText("提醒铃声正在播放")
        .setOngoing(true)
        .setCategory(android.app.Notification.CATEGORY_SERVICE)
        .build()

    companion object {
        const val ACTION_PLAY = "com.bilibili.livemonitor.PLAY_MAGIC_ALERT"
        private const val TAG = "MagicAlertService"
        private const val PLAYBACK_DURATION_MS = 10_000L
        private const val NOTIFICATION_ID_MAGIC_PLAYBACK = 1006

        internal var playerFactory: (Context) -> ExoPlayer = { context ->
            ExoPlayer.Builder(context).build()
        }
        internal var alertSoundProvider: AlertSoundProvider = AlertSoundProvider()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MagicAlertService::class.java).setAction(ACTION_PLAY)
            )
        }
    }
}
