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
import com.bilibili.livemonitor.api.LiveStatusChecker
import com.bilibili.livemonitor.domain.LiveStateDecider
import com.bilibili.livemonitor.receiver.AlarmReceiver
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.AlertSoundProvider
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.worker.LiveCheckWorker
import kotlinx.coroutines.*

class LiveCheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // internal var：测试可注入 fake API 验证检测编排（重试/状态保护/提醒触发）
    internal var api: LiveStatusChecker = BilibiliApi()
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
            preferenceManager.setAlertSuppressed(false)
            LiveCheckWorker.cancelAll(this)
            stopAlertSound()
            stopSelf()
            return START_NOT_STICKY
        }

        // 观播静音命令（点"打开直播间"）：监控不停，本场直播结束前不提醒
        if (intent?.action == ACTION_WATCH_LIVE) {
            AppLogger.d(TAG, "enter watch-live muted mode")
            preferenceManager.setAlertSuppressed(true)
            stopAlertSound()
            updateNotification(lastLiveStatus)
            return START_STICKY
        }

        // 动态流检测（独立 5min Alarm 触发）：只查动态，不走直播/视频检测
        if (intent?.action == ACTION_CHECK_DYNAMICS) {
            if (!preferenceManager.isServiceRunning()) {
                AppLogger.w(TAG, "ACTION_CHECK_DYNAMICS but monitoring disabled, aborting")
                stopSelf()
                return START_NOT_STICKY
            }
            serviceScope.launch {
                try {
                    checkNewDynamics()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "checkNewDynamics error", e)
                }
                scheduleNextDynamicAlarm()
            }
            return START_STICKY
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
                    // 活动监控：视频 + 置顶（与直播同 60s 周期）
                    checkActivities()
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
            // 动态流独立 5min Alarm（风控脆弱，降频 + ±10s 抖动）
            scheduleNextDynamicAlarm()
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
                api.checkLiveStatus(roomId)
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
        // 观播静音：下播（NotLive）自动解除，之后下次开播恢复提醒
        var suppressed = preferenceManager.isAlertSuppressed()
        if (suppressed && LiveStateDecider.shouldClearSuppression(isLive)) {
            suppressed = false
            preferenceManager.setAlertSuppressed(false)
            AppLogger.d(TAG, "stream ended, watch-live mute cleared")
        }

        lastLiveStatus = isLive
        preferenceManager.setLastCheck(System.currentTimeMillis(), isLive, true)

        // 更新通知栏图标
        updateNotification(isLive)

        // 检查是否需要提醒：从未开播转为已开播，或者首次检查就在开播（静音期不提醒）
        val shouldAlert = LiveStateDecider.shouldAlert(lastStatus, isLive, suppressed)

        if (shouldAlert) {
            AppLogger.d(TAG, "triggerAlert")
            triggerAlert()
        }

        lastStatus = isLive
    }

    // ========== B 站全活动监控 ==========

    // 活动监控 API（internal 便于测试注入 fake）
    internal var activityApi: com.bilibili.livemonitor.api.BilibiliActivityApi =
        com.bilibili.livemonitor.api.BilibiliActivityApi()

    private suspend fun checkActivities() {
        // 视频/动态/置顶 共用一个 API 调用（desktop feed/space 一条数据全包）
        if (preferenceManager.isMonitorVideos()
            || preferenceManager.isMonitorDynamics()
            || preferenceManager.isMonitorPinned()
        ) {
            checkDynamicFeed()
        }
    }

    // 由 ACTION_CHECK_DYNAMICS 单独触发（5min 周期），与 60s 周期解耦
    suspend fun checkNewDynamics() {
        if (!preferenceManager.isServiceRunning()) return
        checkDynamicFeed()
    }

    private suspend fun checkDynamicFeed() {
        val result = activityApi.fetchLatestDynamic(
            com.bilibili.livemonitor.api.BilibiliActivityApi.MONITOR_MID
        )
        when (result) {
            is com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok -> {
                val dynamic = result.data
                handleDynamicResult(dynamic)
            }
            is com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.NoData -> {
                AppLogger.d(TAG, "dynamic feed empty, skip")
            }
            is com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Err -> {
                // 失败静默不扰
                AppLogger.w(TAG, "fetchLatestDynamic failed: ${result.reason}")
            }
        }
    }

    private fun handleDynamicResult(dynamic: com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo) {
        val lastId = com.bilibili.livemonitor.domain.ActivityDecider.stringToNullable(
            preferenceManager.getLastDynamicId()
        )

        // 1) 视频变化（DYNAMIC_TYPE_AV 时 avItem 非空，aid 变化）
        val avItem = dynamic.avItem
        if (preferenceManager.isMonitorVideos() && avItem != null) {
            val lastAid = com.bilibili.livemonitor.domain.ActivityDecider.longToNullable(
                preferenceManager.getLastVideoAid()
            )
            preferenceManager.setLastVideoAid(avItem.aid)
            if (com.bilibili.livemonitor.domain.ActivityDecider.shouldAlertVideo(avItem.aid, lastAid)) {
                AppLogger.d(TAG, "new video: aid=${avItem.aid} title=${avItem.title.take(40)}")
                triggerActivityAlert(
                    com.bilibili.livemonitor.domain.ActivityType.Video(avItem.aid, avItem.title)
                )
            }
        }

        // 2) 动态变化（首条动态 id 变化，AV 类型已被上面覆盖）
        if (preferenceManager.isMonitorDynamics() && avItem == null) {
            preferenceManager.setLastDynamicId(dynamic.id)
            if (com.bilibili.livemonitor.domain.ActivityDecider.shouldAlertDynamic(dynamic.id, lastId)) {
                AppLogger.d(TAG, "new dynamic: id=${dynamic.id} text=${dynamic.displayText.take(40)}")
                triggerActivityAlert(
                    com.bilibili.livemonitor.domain.ActivityType.Dynamic(dynamic.id, dynamic.displayText)
                )
            }
        } else {
            // 即便不需要动态通知，也要记录 id（下次跳变判断的基线）
            preferenceManager.setLastDynamicId(dynamic.id)
        }

        // 3) 置顶变化（is_top 状态切换：true↔false）
        if (preferenceManager.isMonitorPinned()) {
            val lastPinnedAid = com.bilibili.livemonitor.domain.ActivityDecider.longToNullable(
                preferenceManager.getLastPinnedAid()
            )
            val currentPinnedAid: Long? = if (dynamic.isTop && avItem != null) avItem.aid else null
            if (currentPinnedAid != lastPinnedAid) {
                preferenceManager.setLastPinnedAid(currentPinnedAid ?: -1)
                AppLogger.d(TAG, "pinned changed: $lastPinnedAid → $currentPinnedAid")
                if (lastPinnedAid != null || currentPinnedAid != null) {
                    // 至少一侧有值才报警（避免首次 null→null 误报）
                    val title = avItem?.title ?: "置顶已取消"
                    triggerActivityAlert(
                        com.bilibili.livemonitor.domain.ActivityType.Pinned(
                            currentPinnedAid ?: 0,
                            title
                        )
                    )
                }
            }
        }
    }

    private fun triggerActivityAlert(type: com.bilibili.livemonitor.domain.ActivityType) {
        when (type) {
            is com.bilibili.livemonitor.domain.ActivityType.Video -> {
                sendVideoNotification(type.aid, type.title, "新视频投稿")
                if (preferenceManager.isAlertRingOnActivity()) playAlertSound()
            }
            is com.bilibili.livemonitor.domain.ActivityType.Pinned -> {
                if (type.aid == 0L) {
                    // 置顶被取消
                    sendTextNotification(LiveMonitorApp.CHANNEL_VIDEO_ALERT_ID, "白绮置顶已取消", null)
                } else {
                    sendVideoNotification(type.aid, type.title, "置顶视频变更")
                }
                if (preferenceManager.isAlertRingOnActivity()) playAlertSound()
            }
            is com.bilibili.livemonitor.domain.ActivityType.Dynamic -> {
                sendDynamicNotification(type.id, type.displayText)
                if (preferenceManager.isAlertRingOnActivity()) playAlertSound()
            }
            is com.bilibili.livemonitor.domain.ActivityType.Live -> {
                // 不应走到这里，开播提醒走 triggerAlert()
            }
        }
    }

    private fun sendTextNotification(channelId: String, title: String, text: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, title.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle(title)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (!text.isNullOrBlank()) builder.setContentText(text.take(50))
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(title.hashCode(), builder.build())
    }

    private fun sendVideoNotification(aid: Long, title: String, prefix: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://video/$aid")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, aid.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, LiveMonitorApp.CHANNEL_VIDEO_ALERT_ID)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle("白绮 $prefix")
            .setContentText(title.take(50))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(LiveMonitorApp.NOTIFICATION_ID_VIDEO, notification)
    }

    private fun sendDynamicNotification(dynamicId: String, displayText: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://dynamic/$dynamicId")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, dynamicId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = displayText.takeIf { it.isNotBlank() } ?: "白绮发布了新动态"
        val notification = NotificationCompat.Builder(this, LiveMonitorApp.CHANNEL_DYNAMIC_ALERT_ID)
            .setSmallIcon(R.drawable.img_on)
            .setContentTitle("白绮新动态")
            .setContentText(text.take(50))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC, notification)
    }

    private fun updateNotification(isLive: Boolean) {
        val notification = createServiceNotification(
            isLive = isLive,
            lastCheckTime = preferenceManager.getLastCheckTime(),
            lastCheckSuccess = preferenceManager.isLastCheckSuccess()
        )
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

    // 提醒铃声播放器引用（internal 便于测试）。此前是局部变量：
    // 服务在 10 秒内被停止时 serviceScope 取消，停止协程被杀，
    // 铃声会永远循环直到进程死亡
    internal var alertPlayer: MediaPlayer? = null

    // 铃声源加载器（internal 便于测试注入 fake）
    internal var alertSoundProvider: AlertSoundProvider = AlertSoundProvider()

    private fun playAlertSound() {
        try {
            alertPlayer = MediaPlayer().apply {
                if (!alertSoundProvider.setupDataSource(
                        this@LiveCheckService, this, preferenceManager.getAlertSoundUri()
                    )) {
                    AppLogger.w(TAG, "all sound sources failed, skip alert")
                    release()
                    alertPlayer = null
                    return@apply
                }
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
                    stopAlertSound()
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "playAlertSound failed", e)
        }
    }

    // 停止提醒铃声（停止监控/onDestroy/10秒自动停止 共用）
    internal fun stopAlertSound() {
        alertPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
                AppLogger.w(TAG, "stop alert sound failed", e)
            }
            it.release()
        }
        alertPlayer = null
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

    private fun createServiceNotification(
        isLive: Boolean,
        lastCheckTime: Long = preferenceManager.getLastCheckTime(),
        lastCheckSuccess: Boolean = preferenceManager.isLastCheckSuccess()
    ): Notification {
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

        return NotificationCompat.Builder(this, LiveMonitorApp.CHANNEL_SERVICE_ID)
            .setSmallIcon(smallIconRes)
            .setLargeIcon(BitmapFactory.decodeResource(resources, iconRes))
            .setContentTitle("牢白播了吗 - $statusText")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setWhen(lastCheckTime.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
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
        // 停止提醒铃声（用户停止监控/服务销毁时铃声必须停）
        stopAlertSound()
        // 只有用户主动停止才清除运行标记；系统杀进程/异常销毁要保留 true，
        // 否则 onDestroy→ServiceRestartReceiver 重启链会被自己卡死
        // （Receiver 启动前检查 prefs，false 会拒绝重启）
        if (isUserStopped) {
            preferenceManager.setServiceRunning(false)
        }
        cancelAlarm()
        cancelDynamicAlarm()

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

    // 动态流独立 5min Alarm（风控脆弱，降频 + ±10s 抖动避免固定间隔被识别）
    private fun scheduleNextDynamicAlarm() {
        if (!preferenceManager.isMonitorDynamics()) return
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, LiveCheckService::class.java).apply {
                action = ACTION_CHECK_DYNAMICS
            }
            val pendingIntent = PendingIntent.getService(
                this, DYNAMIC_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 5min ± 10s 抖动
            val jitter = (Math.random() * 20_000 - 10_000).toLong()
            val triggerAt = System.currentTimeMillis() + DYNAMIC_CHECK_INTERVAL + jitter
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() -> {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                else -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            }
            AppLogger.d(TAG, "scheduleNextDynamicAlarm at $triggerAt")
        } catch (e: Exception) {
            AppLogger.e(TAG, "scheduleNextDynamicAlarm failed", e)
        }
    }

    private fun cancelDynamicAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, LiveCheckService::class.java).apply {
                action = ACTION_CHECK_DYNAMICS
            }
            val pendingIntent = PendingIntent.getService(
                this, DYNAMIC_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "cancelDynamicAlarm failed", e)
        }
    }

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val ACTION_STATUS_CHANGED = "com.bilibili.livemonitor.STATUS_CHANGED"
        const val EXTRA_IS_LIVE = "is_live"
        const val ACTION_STOP_SERVICE = "com.bilibili.livemonitor.STOP_SERVICE"
        const val ACTION_WATCH_LIVE = "com.bilibili.livemonitor.WATCH_LIVE"
        const val ACTION_RESTART_SERVICE = "com.bilibili.livemonitor.RESTART_SERVICE"
        const val ACTION_CHECK_DYNAMICS = "com.bilibili.livemonitor.CHECK_DYNAMICS"
        private const val DEFAULT_ROOM_ID = 11258892L
        private const val CHECK_INTERVAL = 60_000L // 60秒
        private const val DYNAMIC_CHECK_INTERVAL = 5 * 60_000L // 5分钟
        private const val CHECK_TIMEOUT = 25_000L // 单次检测超时25秒
        private const val ERROR_RETRY_DELAY = 15_000L // 错误后15秒重试
        private const val STATUS_RESTORE_MAX_AGE = 600_000L // 进程重启时恢复状态的新鲜度窗口（10分钟）
        private const val ALARM_REQUEST_CODE = 2001
        private const val DYNAMIC_ALARM_REQUEST_CODE = 2002
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
