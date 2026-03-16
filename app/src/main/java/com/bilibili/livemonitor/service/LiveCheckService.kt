package com.bilibili.livemonitor.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.bilibili.livemonitor.AlertActivity
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.MainActivity
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.api.BilibiliApi
import kotlinx.coroutines.*

class LiveCheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkJob: Job? = null
    private lateinit var bilibiliApi: BilibiliApi
    private var roomId: Long = DEFAULT_ROOM_ID
    private var lastStatus: Boolean? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        bilibiliApi = BilibiliApi()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BilibiliLiveMonitor::WakeLock"
        )
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        roomId = intent?.getLongExtra(EXTRA_ROOM_ID, DEFAULT_ROOM_ID) ?: DEFAULT_ROOM_ID
        
        startForeground(
            LiveMonitorApp.NOTIFICATION_ID_SERVICE,
            createServiceNotification(false)
        )

        startChecking()

        return START_STICKY
    }

    private fun startChecking() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkLiveStatus()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(CHECK_INTERVAL)
            }
        }
    }

    private suspend fun checkLiveStatus() {
        val isLive = bilibiliApi.isLiveStreaming(roomId)
        lastLiveStatus = isLive

        // 更新通知栏图标
        updateNotification(isLive)

        // 检查是否需要提醒：从未开播转为已开播，或者首次检查就在开播
        val shouldAlert = if (lastStatus == null) {
            isLive // 首次检查，如果在播就提醒
        } else {
            !lastStatus!! && isLive // 从关播变为开播
        }

        if (shouldAlert) {
            triggerAlert()
        }

        lastStatus = isLive
    }

    private fun updateNotification(isLive: Boolean) {
        val notification = createServiceNotification(isLive)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(LiveMonitorApp.NOTIFICATION_ID_SERVICE, notification)
        
        // 更新应用图标
        updateAppIcon(isLive)
    }

    private fun updateAppIcon(isLive: Boolean) {
        // 通过发送广播让主界面更新图标
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_LIVE, isLive)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun triggerAlert() {
        // 唤醒屏幕
        wakeLock.acquire(10 * 60 * 1000L)

        // 播放铃声
        playAlertSound()

        // 震动
        vibrate()

        // 显示全屏提醒
        showFullScreenAlert()

        // 发送通知
        sendAlertNotification()

        // 释放唤醒锁
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun playAlertSound() {
        try {
            val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            MediaPlayer().apply {
                setDataSource(this@LiveCheckService, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
                
                // 10秒后停止
                serviceScope.launch {
                    delay(10000)
                    if (isPlaying) {
                        stop()
                        release()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 500, 200, 500), -1)
        }
    }

    private fun showFullScreenAlert() {
        val intent = Intent(this, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun sendAlertNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, LiveMonitorApp.CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.img_on)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.img_on))
            .setContentTitle("🎉 白绮开播啦！")
            .setContentText("直播间 11258892 正在直播中，快去看看吧！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(LiveMonitorApp.NOTIFICATION_ID_ALERT, notification)
    }

    private fun createServiceNotification(isLive: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = if (isLive) R.drawable.img_on else R.drawable.img_off
        val smallIconRes = if (isLive) R.drawable.img_on else R.drawable.img_off
        val statusText = if (isLive) "🔴 直播中" else "⚫ 未开播"
        val contentText = if (isLive) 
            "白绮正在直播，快去观看吧！" 
        else 
            "正在监控直播间状态..."

        return NotificationCompat.Builder(this, LiveMonitorApp.CHANNEL_SERVICE_ID)
            .setSmallIcon(smallIconRes)
            .setLargeIcon(BitmapFactory.decodeResource(resources, iconRes))
            .setContentTitle("牢白播了吗 - $statusText")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        checkJob?.cancel()
        serviceScope.cancel()
        isRunning = false
        lastLiveStatus = false

        // 发送广播重启服务
        val broadcastIntent = Intent("com.bilibili.livemonitor.RESTART_SERVICE")
        sendBroadcast(broadcastIntent)
    }

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val ACTION_STATUS_CHANGED = "com.bilibili.livemonitor.STATUS_CHANGED"
        const val EXTRA_IS_LIVE = "is_live"
        private const val DEFAULT_ROOM_ID = 11258892L
        private const val CHECK_INTERVAL = 60_000L // 60秒

        @Volatile
        var isRunning = false

        @Volatile
        var lastLiveStatus = false
    }
}
