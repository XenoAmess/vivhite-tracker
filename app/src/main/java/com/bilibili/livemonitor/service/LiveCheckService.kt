package com.bilibili.livemonitor.service

import android.app.AlarmManager
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
import com.bilibili.livemonitor.domain.LiveStateDecider
import com.bilibili.livemonitor.receiver.AlarmReceiver
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.worker.LiveCheckWorker
import kotlinx.coroutines.*

class LiveCheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bilibiliApi: BilibiliApi
    private lateinit var preferenceManager: PreferenceManager
    private var roomId: Long = DEFAULT_ROOM_ID
    private var lastStatus: Boolean? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    // 用于保护检测的轻量级WakeLock
    private lateinit var checkWakeLock: PowerManager.WakeLock

    // 防止并发检查
    private val isChecking = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "onCreate")
        bilibiliApi = BilibiliApi()
        preferenceManager = PreferenceManager(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BilibiliLiveMonitor::WakeLock"
        )
        // 初始化用于检测的轻量级WakeLock，防止Doze模式影响检测
        checkWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BilibiliLiveMonitor::CheckWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // 必须在5秒内调用startForeground，放在onCreate确保及时性
        startForeground(
            LiveMonitorApp.NOTIFICATION_ID_SERVICE,
            createServiceNotification(lastLiveStatus)
        )

        // 停止权威位：prefs=false 表示用户已停止，本次启动是 START_STICKY 重投
        // 或残留 Worker/Alarm 的孤儿拉起，必须立即自毁。
        // （修复 instrumented test 发现的真 bug：此前 onCreate 无条件把
        // prefs 刷回 true，用户停止后服务会被任意滞留启动复活并继续监控）
        if (!preferenceManager.isServiceRunning()) {
            AppLogger.w(TAG, "monitoring disabled in prefs, aborting stray start")
            isUserStopped = true
            stopSelf()
            return
        }

        // 进程重启时恢复上次状态（10分钟内），避免直播中进程死亡导致重复提醒
        lastStatus = LiveStateDecider.restoreLastStatus(
            lastCheckTime = preferenceManager.getLastCheckTime(),
            lastCheckSuccess = preferenceManager.isLastCheckSuccess(),
            lastCheckLive = preferenceManager.isLastCheckLive(),
            now = System.currentTimeMillis(),
            maxAgeMillis = STATUS_RESTORE_MAX_AGE
        )
        isRunning = true

        // 确保WorkManager兜底任务已注册
        LiveCheckWorker.schedulePeriodic(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand action=${intent?.action}")
        // 处理停止命令
        if (intent?.action == ACTION_STOP_SERVICE) {
            isUserStopped = true
            preferenceManager.setServiceRunning(false)
            LiveCheckWorker.cancelAll(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // 停止权威位二次检查：onCreate 自毁后仍可能有已入队的 intent 被投递，
        // 这里必须再次确认，不能把 prefs 刷回 true（否则用户停止会被复活）
        if (!preferenceManager.isServiceRunning()) {
            AppLogger.w(TAG, "onStartCommand but monitoring disabled, aborting")
            isUserStopped = true
            stopSelf()
            return START_NOT_STICKY
        }

        val newRoomId = intent?.getLongExtra(EXTRA_ROOM_ID, DEFAULT_ROOM_ID) ?: DEFAULT_ROOM_ID

        // 如果房间号改变，重置状态
        if (newRoomId != roomId) {
            roomId = newRoomId
            lastStatus = null
        }

        // startForeground已在onCreate中调用，这里只更新通知
        updateNotification(lastLiveStatus)

        // 执行检查（由AlarmManager触发或用户启动触发）
        serviceScope.launch {
            if (isChecking.compareAndSet(false, true)) {
                try {
                    checkLiveStatusWithRetry()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "checkLiveStatus error", e)
                } finally {
                    isChecking.set(false)
                }
            } else {
                AppLogger.d(TAG, "check already in progress, skip")
            }
            // 检查完成后设置下一次Alarm（作为保底，AlarmReceiver也会设置）
            scheduleNextCheckAlarm()
        }

        return START_STICKY
    }

    private suspend fun checkLiveStatusWithRetry() {
        val result = checkLiveStatusOnce()
        if (LiveStateDecider.shouldRetry(result)) {
            AppLogger.w(TAG, "first check failed: ${(result as BilibiliApi.LiveStatus.Error).reason}, retry in ${ERROR_RETRY_DELAY / 1000}s")
            delay(ERROR_RETRY_DELAY)
            val retryResult = checkLiveStatusOnce()
            if (retryResult is BilibiliApi.LiveStatus.Error) {
                AppLogger.e(TAG, "retry also failed: ${retryResult.reason}")
                // 两次都失败，记录但不更新状态，等待下一个周期
                preferenceManager.setLastCheck(System.currentTimeMillis(), lastLiveStatus, false)
            }
        }
    }

    private suspend fun checkLiveStatusOnce(): BilibiliApi.LiveStatus {
        AppLogger.d(TAG, "checkLiveStatus roomId=$roomId")
        // 使用WakeLock保护检测过程，防止Doze模式影响
        if (!checkWakeLock.isHeld) {
            checkWakeLock.acquire(60_000L)
        }
        try {
            // 添加超时保护，确保检测不会挂起太久
            val status = withTimeoutOrNull(CHECK_TIMEOUT) {
                bilibiliApi.checkLiveStatus(roomId)
            } ?: BilibiliApi.LiveStatus.Error("check timeout after ${CHECK_TIMEOUT}ms")

            AppLogger.d(TAG, "checkLiveStatus result=$status lastStatus=$lastStatus")

            when (status) {
                is BilibiliApi.LiveStatus.Live -> handleResult(true)
                is BilibiliApi.LiveStatus.NotLive -> handleResult(false)
                is BilibiliApi.LiveStatus.Error -> {
                    // 错误不更新状态，由调用方决定是否重试
                }
            }
            return status
        } finally {
            // 确保释放WakeLock
            if (checkWakeLock.isHeld) {
                checkWakeLock.release()
            }
        }
    }

    private fun handleResult(isLive: Boolean) {
        lastLiveStatus = isLive
        preferenceManager.setLastCheck(System.currentTimeMillis(), isLive, true)

        // 更新通知栏图标
        updateNotification(isLive)

        // 检查是否需要提醒：从未开播转为已开播，或者首次检查就在开播
        val shouldAlert = LiveStateDecider.shouldAlert(lastStatus, isLive)

        if (shouldAlert) {
            AppLogger.d(TAG, "triggerAlert")
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
            AppLogger.e(TAG, "playAlertSound failed", e)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLogger.w(TAG, "onTaskRemoved, scheduling restart")
        // 部分ROM划掉任务卡片会杀服务，立即排Alarm和Worker双保险拉起
        if (preferenceManager.isServiceRunning()) {
            scheduleNextCheckAlarm()
            LiveCheckWorker.scheduleOneTime(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d(TAG, "onDestroy isUserStopped=$isUserStopped")
        serviceScope.cancel()
        isRunning = false
        lastLiveStatus = false
        // 只有用户主动停止才清除运行标记；系统杀进程/异常销毁要保留 true，
        // 否则 onDestroy→ServiceRestartReceiver 重启链会被自己卡死
        // （Receiver 启动前检查 prefs，false 会拒绝重启）
        if (isUserStopped) {
            preferenceManager.setServiceRunning(false)
        }
        cancelAlarm()

        // 释放所有WakeLock
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        if (::checkWakeLock.isInitialized && checkWakeLock.isHeld) {
            checkWakeLock.release()
        }

        // 只有非用户手动停止时才发送广播重启服务
        if (!isUserStopped) {
            try {
                val broadcastIntent = Intent(this, com.bilibili.livemonitor.receiver.ServiceRestartReceiver::class.java).apply {
                    action = ACTION_RESTART_SERVICE
                }
                sendBroadcast(broadcastIntent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "send restart broadcast failed", e)
            }
        }
        // 重置标志
        isUserStopped = false
    }

    private fun scheduleNextCheckAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = System.currentTimeMillis() + CHECK_INTERVAL
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() -> {
                    AppLogger.w(TAG, "exact alarm not granted, fallback to inexact")
                    // 未授权精确闹钟权限，回退到非精确版本
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            }
            AppLogger.d(TAG, "scheduleNextCheckAlarm at $triggerAt")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "scheduleNextCheckAlarm SecurityException", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "scheduleNextCheckAlarm failed", e)
        }
    }

    private fun cancelAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            AppLogger.d(TAG, "cancelAlarm")
        } catch (e: Exception) {
            AppLogger.e(TAG, "cancelAlarm failed", e)
        }
    }

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val ACTION_STATUS_CHANGED = "com.bilibili.livemonitor.STATUS_CHANGED"
        const val EXTRA_IS_LIVE = "is_live"
        const val ACTION_STOP_SERVICE = "com.bilibili.livemonitor.STOP_SERVICE"
        const val ACTION_RESTART_SERVICE = "com.bilibili.livemonitor.RESTART_SERVICE"
        private const val DEFAULT_ROOM_ID = 11258892L
        private const val CHECK_INTERVAL = 60_000L // 60秒
        private const val CHECK_TIMEOUT = 25_000L // 单次检测超时25秒
        private const val ERROR_RETRY_DELAY = 15_000L // 错误后15秒重试
        private const val STATUS_RESTORE_MAX_AGE = 600_000L // 进程重启时恢复状态的新鲜度窗口（10分钟）
        private const val ALARM_REQUEST_CODE = 2001
        private const val TAG = "LiveCheckService"

        @Volatile
        var isRunning = false

        @Volatile
        var lastLiveStatus = false

        // 标记是否是用户手动停止，避免自动重启
        @Volatile
        var isUserStopped = false
    }
}
