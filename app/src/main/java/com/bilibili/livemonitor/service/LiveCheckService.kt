package com.bilibili.livemonitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.bilibili.livemonitor.LiveMonitorApp
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
import kotlinx.coroutines.sync.Mutex

class LiveCheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // internal var：测试可注入 fake API 验证检测编排（重试/状态保护/提醒触发）
    internal var api: LiveStatusChecker = BilibiliApi()
    private lateinit var preferenceManager: PreferenceManager
    // @Volatile：主线程写（房间变更/onStartCommand）、IO 协程读（检测），跨线程可见
    @Volatile private var roomId: Long = DEFAULT_ROOM_ID
    @Volatile private var lastStatus: Boolean? = null
    private var monitoringGeneration: Long = 0L
    private var stopRequestedByUser = false
    private var stopRequestedGeneration: Long = NO_MONITORING_GENERATION

    // 用于保护检测的轻量级WakeLock
    private lateinit var checkWakeLock: PowerManager.WakeLock

    // 防止并发检查；internal 让单测能等待一次检查完整处理完毕。
    internal val isChecking = java.util.concurrent.atomic.AtomicBoolean(false)

    // 常规视频检查与独立动态 Alarm 会打同一 feed；不能并发读写同一批基线。
    private val activityCheckMutex = Mutex()

    // 通知渲染层（从本服务拆出）。buildVideoIntent/buildDynamicIntent 留在服务
    // 以保持测试注入位（bilibiliInstalled）不变；builder 通过 lambda 在调用时读取最新值。
    private val notificationBuilder: NotificationBuilder by lazy {
        NotificationBuilder(this, DEFAULT_ROOM_ID, { buildVideoIntent(it) }, { buildDynamicIntent(it) })
    }

    // 场次记录 + 主题变化追踪（从本服务拆出），决策逻辑可单测
    private val streamSessionTracker: StreamSessionTracker by lazy {
        StreamSessionTracker(
            this, preferenceManager, serviceScope,
            onStreamEnd = { notificationBuilder.sendStreamEnd(it) },
            onTitleChange = { notificationBuilder.sendTitleChange(it) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "onCreate")
        // androidTest 注入位（同进程 instrumented test 用 fake api 驱动完整提醒链路）；
        // 生产恒为 null。必须在任何检测发生前应用
        apiOverride?.let { api = it }
        preferenceManager = PreferenceManager(this)
        monitoringGeneration = preferenceManager.getMonitoringGeneration()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
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
            notificationBuilder.buildServiceNotification(
                isLive = lastLiveStatus,
                lastCheckTime = preferenceManager.getLastCheckTime(),
                lastCheckSuccess = preferenceManager.isLastCheckSuccess()
            )
        )

        // 停止权威位：prefs=false 表示用户已停止，本次启动是 START_STICKY 重投
        // 或残留 Worker/Alarm 的孤儿拉起，必须立即自毁。
        // （修复 instrumented test 发现的真 bug：此前 onCreate 无条件把
        // prefs 刷回 true，用户停止后服务会被任意滞留启动复活并继续监控）
        if (!preferenceManager.isServiceRunning()) {
            AppLogger.w(TAG, "monitoring disabled in prefs, aborting stray start")
            stopRequestedByUser = true
            stopRequestedGeneration = monitoringGeneration
            stopSelf()
            return
        }

        // 进程重启时恢复上次状态（10分钟内），避免直播中进程死亡导致重复提醒
        lastStatus = preferenceManager.getRecentLastStatus(STATUS_RESTORE_MAX_AGE)
        // 用恢复值同步静态 lastLiveStatus，避免重启后 Worker/UI 读到 stale 值
        // （onDestroy 只重置静态而不重置 prefs 的 lastCheckLive，两者曾可漂移）
        lastLiveStatus = lastStatus ?: false

        // 观播静音新鲜度清除（A 兜底）：静音标记只在"监控持续在跑"时有意义。
        // 服务若在下播窗口期被杀（lastCheck 过期/失败/已知下播），标记已失真，
        // 不清除则之后开播会被 shouldAlert 的 suppressed 分支永久吞掉提醒。
        if (preferenceManager.isAlertSuppressed()) {
            val checkTime = preferenceManager.getLastCheckTime()
            val checkSuccess = preferenceManager.isLastCheckSuccess()
            val checkLive = preferenceManager.isLastCheckLive()
            val stale = !checkSuccess ||
                System.currentTimeMillis() - checkTime > STATUS_RESTORE_MAX_AGE ||
                !checkLive
            if (stale) {
                AppLogger.w(TAG, "clearing stale watch-live mute on startup: success=$checkSuccess live=$checkLive age=${System.currentTimeMillis() - checkTime}ms")
                preferenceManager.setAlertSuppressed(false)
                preferenceManager.setSuppressedLiveStart("")
            }
        }

        isRunning = true

        // 确保WorkManager兜底任务已注册
        LiveCheckWorker.schedulePeriodic(this)
        ensureDynamicAlarmScheduled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand action=${intent?.action}")
        // 处理停止命令
        if (intent?.action == ACTION_STOP_SERVICE) {
            val requestedGeneration = intent.getLongExtra(
                EXTRA_MONITORING_GENERATION,
                preferenceManager.getMonitoringGeneration()
            )
            if (requestedGeneration != preferenceManager.getMonitoringGeneration()) {
                AppLogger.w(
                    TAG,
                    "ignoring stale STOP: requested=$requestedGeneration current=${preferenceManager.getMonitoringGeneration()}"
                )
                return START_STICKY
            }
            stopRequestedByUser = true
            stopRequestedGeneration = requestedGeneration
            preferenceManager.setServiceRunning(false)
            preferenceManager.setAlertSuppressed(false)
            LiveCheckWorker.cancelAll(this)
            cancelScheduledChecks(this)
            stopAlertSound()
            // 新的 start command 已到达时不能把它一并杀掉；新会话会接管同一服务实例。
            if (!stopSelfResult(startId)) {
                AppLogger.d(TAG, "STOP superseded by a newer start command")
                stopRequestedByUser = false
                stopRequestedGeneration = NO_MONITORING_GENERATION
            }
            return START_NOT_STICKY
        }

        // 全屏提醒页被用户处理后只停铃，不改变监控开关或会话状态。
        if (intent?.action == ACTION_STOP_ALERT) {
            stopAlertSoundSync()
            return if (preferenceManager.isServiceRunning()) START_STICKY else START_NOT_STICKY
        }

        // 停止权威位二次检查：onCreate 自毁后仍可能有已入队的 intent 被投递，
        // 这里必须再次确认，不能把 prefs 刷回 true（否则用户停止会被复活）
        if (!preferenceManager.isServiceRunning()) {
            AppLogger.w(TAG, "onStartCommand but monitoring disabled, aborting")
            stopRequestedByUser = true
            stopRequestedGeneration = preferenceManager.getMonitoringGeneration()
            stopSelf()
            return START_NOT_STICKY
        }

        val requestedGeneration = intent?.getLongExtra(
            EXTRA_MONITORING_GENERATION,
            NO_MONITORING_GENERATION
        ) ?: NO_MONITORING_GENERATION
        val currentGeneration = preferenceManager.getMonitoringGeneration()
        if (requestedGeneration != NO_MONITORING_GENERATION
            && requestedGeneration != currentGeneration
        ) {
            AppLogger.w(
                TAG,
                "ignoring stale START: requested=$requestedGeneration current=$currentGeneration"
            )
            return START_STICKY
        }
        if (monitoringGeneration != currentGeneration) {
            AppLogger.d(TAG, "adopting current monitoring generation $currentGeneration")
            monitoringGeneration = currentGeneration
            stopRequestedByUser = false
            stopRequestedGeneration = NO_MONITORING_GENERATION
        }

        // 观播静音命令（点"打开直播间"）：监控不停，本场直播结束前不提醒。
        // 必须放在 prefs 权威位检查之后，停止后的残留命令不能重置静音状态。
        if (intent?.action == ACTION_WATCH_LIVE) {
            AppLogger.d(TAG, "enter watch-live muted mode")
            preferenceManager.setAlertSuppressed(true)
            preferenceManager.setSuppressedLiveStart(preferenceManager.getLastLiveStartTime())
            AppLogger.d(TAG, "watch-live mute set: bound=${preferenceManager.getSuppressedLiveStart()}")
            stopAlertSound()
            updateNotification(lastLiveStatus)
            return START_STICKY
        }

        // 活动流检测（独立 5min Alarm 触发）：视频、动态、置顶共用同一风险敏感端点，
        // 必须一起降频，不能让视频/置顶开关把动态接口重新拉回每分钟。
        if (intent?.action == ACTION_CHECK_DYNAMICS) {
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
            val started = isChecking.compareAndSet(false, true)
            if (started) {
                try {
                    checkLiveStatusWithRetry()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "checkLiveStatus error", e)
                } finally {
                    isChecking.set(false)
                }
                // 检查已启动：设置下一次Alarm（作为保底，AlarmReceiver也会设置）
                scheduleNextCheckAlarm()
            } else {
                // 在检中跳过：不重排 60s Alarm（AlarmReceiver 已排好下一次），
                // 否则慢检查期间每次到达的 Alarm 都把周期往后推，造成节奏漂移。
                AppLogger.d(TAG, "check already in progress, skip")
            }
            // 动态流独立 5min Alarm：常规 60s 检查只确保它存在，不能每分钟重置
            // 触发时间，否则 Alarm 永远到不了真正的动态检查。
            ensureDynamicAlarmScheduled()
        }

        return START_STICKY
    }

    private suspend fun checkLiveStatusWithRetry() {
        // 单把锁覆盖 首次检测 + 重试间隔 + 重试 全程，防止 Doze 在间隔期休眠把重试推迟
        if (!checkWakeLock.isHeld) {
            checkWakeLock.acquire(CHECK_WAKE_LOCK_TIMEOUT)
        }
        try {
            val result = checkLiveStatusOnce()
            if (LiveStateDecider.shouldRetry(result)) {
                // shouldRetry 保证 result 是 Error，但用 when 而非强转，避免逻辑变更时 ClassCastException
                val reason = when (result) {
                    is BilibiliApi.LiveStatus.Error -> result.reason
                    else -> "unknown"
                }
                AppLogger.w(TAG, "first check failed: $reason, retry in ${ERROR_RETRY_DELAY / 1000}s")
                delay(ERROR_RETRY_DELAY)
                val retryResult = checkLiveStatusOnce()
                if (retryResult is BilibiliApi.LiveStatus.Error) {
                    AppLogger.e(TAG, "retry also failed: ${retryResult.reason}")
                    // 两次都失败，记录但不更新状态，等待下一个周期
                    preferenceManager.setLastCheck(System.currentTimeMillis(), lastLiveStatus, false)
                }
            }
        } finally {
            if (checkWakeLock.isHeld) {
                checkWakeLock.release()
            }
        }
    }

    private suspend fun checkLiveStatusOnce(): BilibiliApi.LiveStatus {
        AppLogger.d(TAG, "checkLiveStatus roomId=$roomId")
        // WakeLock 由 checkLiveStatusWithRetry 统一持有（覆盖 检测+重试 全程），这里不再单独加锁
        // 添加超时保护，确保检测不会挂起太久
        val status = withTimeoutOrNull(CHECK_TIMEOUT) {
            api.checkLiveStatus(roomId)
        } ?: BilibiliApi.LiveStatus.Error("check timeout after ${CHECK_TIMEOUT}ms")

        AppLogger.d(TAG, "checkLiveStatus result=$status lastStatus=$lastStatus")

        when (status) {
            is BilibiliApi.LiveStatus.Live -> {
                handleResult(true, status.liveStartTime, status.title)
                streamSessionTracker.recordPopularity(status.online)
                streamSessionTracker.collectStreamCover(roomId)
            }
            is BilibiliApi.LiveStatus.NotLive -> handleResult(false)
            is BilibiliApi.LiveStatus.Error -> {
                // 错误不更新状态，由调用方决定是否重试
            }
        }
        return status
    }

    private fun handleResult(isLive: Boolean, liveStartTime: String? = null, liveTitle: String? = null) {
        // 记录本场直播的 live_start_time（供置静音时绑定参照）
        if (isLive && liveStartTime != null) {
            preferenceManager.setLastLiveStartTime(liveStartTime)
        }
        // 每次确认在播都刷新"存活证据"时间戳（进程死亡后 reconcile 残留场次的闭合上限）
        if (isLive) {
            preferenceManager.setLastLiveObservedTime(System.currentTimeMillis())
        }

        // 观播静音解除判定：下播即解除；检测到新一场直播（live_start_time 与
        // 置静音时绑定的不一致）也解除——修复"置静音后服务在下播窗口期被杀，
        // 标记卡死导致之后所有开播都不响铃"的真机 bug
        var suppressed = preferenceManager.isAlertSuppressed()
        if (suppressed) {
            val bound = preferenceManager.getSuppressedLiveStart()
            val isNewSession = isLive &&
                bound.isNotBlank() &&
                liveStartTime != null &&
                liveStartTime != bound
            if (LiveStateDecider.shouldClearSuppression(isLive, isNewSession)) {
                val reason = if (!isLive) "notlive" else "new-session"
                AppLogger.d(TAG, "watch-live mute cleared: reason=$reason bound=$bound current=$liveStartTime")
                suppressed = false
                preferenceManager.setAlertSuppressed(false)
                preferenceManager.setSuppressedLiveStart("")
                if (isNewSession) {
                    // 新一场 = 新的开播跳变：重置 lastStatus 让 shouldAlert 按
                    // null→Live 触发提醒，否则内存中 lastStatus=true 会吞掉本次提醒
                    lastStatus = null
                }
            }
        }

        lastLiveStatus = isLive
        preferenceManager.setLastCheck(System.currentTimeMillis(), isLive, true)

        // 更新通知栏图标 + 桌面小组件
        updateNotification(isLive)
        com.bilibili.livemonitor.widget.LiveStatusWidgetProvider.updateAll(this)

        // 检查是否需要提醒：从未开播转为已开播，或者首次检查就在开播（静音期不提醒）
        val shouldAlert = LiveStateDecider.shouldAlert(lastStatus, isLive, suppressed)
        val wasLive = lastStatus == true

        // 场次记录：任何 未开播→开播 跳变都记（含观播静音期，静音只影响提醒不影响记录）
        if (isLive && !wasLive) {
            streamSessionTracker.recordStreamStart(liveStartTime, liveTitle)
        }
        if (shouldAlert) {
            AppLogger.d(TAG, "triggerAlert")
            triggerAlert()
        }
        // 下播：闭合场次 + 下播提醒
        if (!isLive && wasLive) {
            streamSessionTracker.recordStreamEnd()
        }
        // 无跳变的 NotLive（进程死亡跨过下播、状态恢复超龄）：静默补闭合残留开放行，
        // 否则它会挂到下一场开播被错闭合成数天长的假场次。与 recordStreamEnd 经
        // wasLive 门控互斥，不会双跑
        if (!isLive && !wasLive) {
            streamSessionTracker.reconcileOpenSessionIfNotLive()
        }
        // 直播中主题变化提醒（每次 Live 轮询都追踪）
        if (isLive) {
            streamSessionTracker.trackTitleChange(liveTitle)
        }

        lastStatus = isLive
        // 勿扰错过提醒：勿扰窗口内静音过的开播，等窗口结束后补一条汇总
        maybeSendQuietMissedSummary()
    }

    // ========== B 站全活动监控 ==========

    // 活动监控 API（internal 便于测试注入 fake）
    internal var activityApi: com.bilibili.livemonitor.api.BilibiliActivityApi =
        com.bilibili.livemonitor.api.BilibiliActivityApi()

    // 由 ACTION_CHECK_DYNAMICS 单独触发（5min 周期）。三个活动功能共用 feed，
    // 所以一次请求统一处理视频、动态和置顶，而不是随直播检查每分钟打接口。
    private suspend fun checkNewDynamics() {
        if (!preferenceManager.isServiceRunning() || !isActivityMonitoringEnabled()) return
        checkDynamicFeed()
    }

    private fun isActivityMonitoringEnabled(): Boolean =
        preferenceManager.isMonitorVideos()
            || preferenceManager.isMonitorDynamics()
            || preferenceManager.isMonitorPinned()

    private suspend fun checkDynamicFeed() {
        if (!activityCheckMutex.tryLock()) {
            AppLogger.d(TAG, "activity check already in progress, skip")
            return
        }
        try {
        // desktop 端点间歇性返回 code=0 但 items=[]（2026-08-02 实测约 1/6 抽风率），
        // Err/NoData 统一等 15s 重试一次（对齐直播检测策略），避免偶发漏检
            when (val result = fetchDynamicOnce()) {
                is com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok -> {
                    handleDynamicResult(result.data)
                }
                else -> {
                    AppLogger.w(TAG, "dynamic check failed ($result), retry in ${dynamicRetryDelayMillis / 1000}s")
                    kotlinx.coroutines.delay(dynamicRetryDelayMillis)
                    when (val retry = fetchDynamicOnce()) {
                        is com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok -> {
                            AppLogger.d(TAG, "dynamic retry succeeded")
                            handleDynamicResult(retry.data)
                        }
                        else -> AppLogger.w(TAG, "dynamic retry also failed ($retry)")
                    }
                }
            }
        } finally {
            activityCheckMutex.unlock()
        }
    }

    // internal 注入位：单测置 0 避免 15s 重试阻塞 isChecking 闸门
    internal var dynamicRetryDelayMillis: Long = ERROR_RETRY_DELAY

    private suspend fun fetchDynamicOnce(): com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult<com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo> {
        return activityApi.fetchLatestDynamic(
            com.bilibili.livemonitor.util.BiliTargets.MONITOR_MID
        )
    }

    private fun handleDynamicResult(dynamic: com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo) {
        val lastId = com.bilibili.livemonitor.domain.ActivityDecider.stringToNullable(
            preferenceManager.getLastDynamicId()
        )

        // 1) 视频变化：最新动态本身是视频时直接用它；若最新动态是图文，仍要从
        // feed 中保留的最新视频（或唯一的置顶视频）推进视频基线。
        val avItem = dynamic.avItem
        val latestVideo = dynamic.latestAvItem ?: avItem ?: dynamic.pinnedAvItem
        if (preferenceManager.isMonitorVideos() && latestVideo != null) {
            val lastAid = com.bilibili.livemonitor.domain.ActivityDecider.longToNullable(
                preferenceManager.getLastVideoAid()
            )
            preferenceManager.setLastVideoAid(latestVideo.aid)
            if (com.bilibili.livemonitor.domain.ActivityDecider.shouldAlertVideo(latestVideo.aid, lastAid)) {
                AppLogger.d(TAG, "new video: aid=${latestVideo.aid} title=${latestVideo.title.take(40)}")
                triggerActivityAlert(
                    com.bilibili.livemonitor.domain.ActivityType.Video(latestVideo.aid, latestVideo.title)
                )
            }
        }

        // 2) 动态变化（首条动态 id 变化，AV 类型已被上面覆盖；按勾选的类型过滤）
        if (preferenceManager.isMonitorDynamics() && avItem == null) {
            preferenceManager.setLastDynamicId(dynamic.id)
            if (preferenceManager.isDynamicTypeEnabled(dynamic.type) &&
                com.bilibili.livemonitor.domain.ActivityDecider.shouldAlertDynamic(dynamic.id, lastId)
            ) {
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
            // latest dynamic 与置顶视频是独立字段：feed 首项通常是置顶，解析层会跳过它
            // 以免吞掉新动态，但这里仍必须使用它判断置顶变更。
            val pinnedVideo = dynamic.pinnedAvItem ?: avItem?.takeIf { dynamic.isTop }
            val currentPinnedAid: Long? = pinnedVideo?.aid
            if (currentPinnedAid != lastPinnedAid) {
                preferenceManager.setLastPinnedAid(currentPinnedAid ?: -1)
                AppLogger.d(TAG, "pinned changed: $lastPinnedAid → $currentPinnedAid")
                if (com.bilibili.livemonitor.domain.ActivityDecider.shouldAlertPinned(
                        currentPinnedAid, lastPinnedAid
                    )
                ) {
                    val title = pinnedVideo?.title ?: "置顶已取消"
                    triggerActivityAlert(
                        com.bilibili.livemonitor.domain.ActivityType.Pinned(
                            currentPinnedAid ?: 0,
                            title
                        )
                    )
                }
            }
        }

        // 4) 开播预告（LIVE_RCMD）：预告时间在 24h 内且未提醒过 → 提醒一次
        dynamic.liveRcmd?.let { rcmd ->
            val now = System.currentTimeMillis()
            if (com.bilibili.livemonitor.domain.LiveReminderDecider.shouldRemind(
                    rcmd.liveStartMs,
                    now,
                    preferenceManager.getLastRemindedLiveDynamicId(),
                    rcmd.dynamicId
                )
            ) {
                preferenceManager.setLastRemindedLiveDynamicId(rcmd.dynamicId)
                AppLogger.d(TAG, "live reminder: start=${rcmd.liveStartMs} title=${rcmd.title}")
                notificationBuilder.sendLiveReminder(rcmd)
            }
        }
    }

    private fun triggerActivityAlert(type: com.bilibili.livemonitor.domain.ActivityType) {
        val ring = preferenceManager.isAlertRingOnActivity() && !isInQuietHours()
        when (type) {
            is com.bilibili.livemonitor.domain.ActivityType.Video -> {
                notificationBuilder.sendVideo(type.aid, type.title, "新视频投稿")
                if (ring) playAlertSound()
            }
            is com.bilibili.livemonitor.domain.ActivityType.Pinned -> {
                if (type.aid == 0L) {
                    // 置顶被取消
                    notificationBuilder.sendText(LiveMonitorApp.CHANNEL_VIDEO_ALERT_ID, "白绮置顶已取消", null)
                } else {
                    notificationBuilder.sendVideo(type.aid, type.title, "置顶视频变更")
                }
                if (ring) playAlertSound()
            }
            is com.bilibili.livemonitor.domain.ActivityType.Dynamic -> {
                notificationBuilder.sendDynamic(type.id, type.displayText)
                if (ring) playAlertSound()
            }
            is com.bilibili.livemonitor.domain.ActivityType.Live -> {
                // 不应走到这里，开播提醒走 triggerAlert()
            }
        }
    }

    // 勿扰时段：起止分钟数来自 prefs（默认 23:00 → 07:00，跨午夜），开关默认关
    private fun isInQuietHours(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        return com.bilibili.livemonitor.domain.QuietHoursDecider.isInQuietHours(
            nowMinutes,
            preferenceManager.getQuietStartMinutes(),
            preferenceManager.getQuietEndMinutes(),
            preferenceManager.isQuietHoursEnabled()
        )
    }

    // 勿扰错过提醒：静音开播的 marker 在勿扰结束后补一条汇总（只发一次，发完清 marker）
    private fun maybeSendQuietMissedSummary() {
        val missedTs = preferenceManager.getQuietMissedLiveTs()
        if (missedTs <= 0L) return
        if (isInQuietHours()) return
        preferenceManager.setQuietMissedLiveTs(0L)
        notificationBuilder.sendQuietMissedSummary(missedTs, preferenceManager.getQuietMissedLiveTitle())
    }

    /**
     * 已安装的 B 站客户端包名（tv.danmaku.bili 优先，其次 HD 版），都没装返回 null。
     * 两个包都已在 manifest <queries> 声明，可见性无问题。
     * internal 注入位：单测控制安装态。
     */
    internal var bilibiliInstalled: (String) -> Boolean = { pkg ->
        try {
            packageManager.getPackageInfo(pkg, 0); true
        } catch (_: Exception) {
            false
        }
    }

    internal fun resolveBiliPackage(): String? {
        for (pkg in listOf("tv.danmaku.bili", "tv.danmaku.bilibilihd")) {
            if (bilibiliInstalled(pkg)) return pkg
        }
        return null
    }

    /**
     * B 站链接强投递：官方 web 格式 + setPackage 强制投递给已装客户端
     * （liveRoomAppIntent 同款策略，绕开 scheme 路由猜测与 resolveActivity
     * 不确定性——bilibili://dynamic/{id} 无路由、bilibili://dynamic/detail/{id}
     * 真机实测解析为空，均废弃）。未装客户端则不加 setPackage，浏览器兜底。
     */
    private fun buildBiliIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            resolveBiliPackage()?.let { setPackage(it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    internal fun buildDynamicIntent(dynamicId: String): Intent =
        buildBiliIntent("https://t.bilibili.com/$dynamicId")

    internal fun buildVideoIntent(aid: Long): Intent =
        buildBiliIntent("https://www.bilibili.com/video/av$aid")

    private fun updateNotification(isLive: Boolean) {
        notificationBuilder.postServiceNotification(
            isLive = isLive,
            lastCheckTime = preferenceManager.getLastCheckTime(),
            lastCheckSuccess = preferenceManager.isLastCheckSuccess()
        )

        // 更新应用图标由 MainActivity.onResume/updateUI 从 prefs 刷新（历史遗留的
        // ACTION_STATUS_CHANGED 广播无接收者，已删除；界面在回到前台时自动更新）
    }

    private fun triggerAlert() {
        // 勿扰时段：不响铃/不震动/不全屏，只发静音通知（醒来能看到「开播了」）
        if (isInQuietHours()) {
            AppLogger.w(TAG, "quiet hours active, silent alert only")
            // 记录被静音的开播时间与标题，勿扰结束后补「错过提醒」汇总
            preferenceManager.setQuietMissedLiveTs(System.currentTimeMillis())
            preferenceManager.setQuietMissedLiveTitle(preferenceManager.getLastLiveTitle())
            notificationBuilder.sendSilentAlert()
            return
        }
        // 服务是铃声的唯一所有者；全屏页只负责展示，避免两套 ExoPlayer 叠音。
        playAlertSound()
        vibrate()
        // Android 10+ 不保证后台 startActivity 成功，交给高优先级通知的
        // fullScreenIntent 处理锁屏/前台展示。
        notificationBuilder.sendAlert()
    }

    // 提醒铃声播放器引用（internal 便于测试）。此前是局部变量：
    // 服务在 10 秒内被停止时 serviceScope 取消，停止协程被杀，
    // 铃声会永远循环直到进程死亡
    internal var alertPlayer: ExoPlayer? = null

    // 铃声源加载器（internal 便于测试注入 fake）
    internal var alertSoundProvider: AlertSoundProvider = AlertSoundProvider()

    // ExoPlayer 工厂（internal 便于测试注入 fake）。
    // Robolectric 无法构造 ExoPlayer，真机/模拟器上用默认实现。
    internal var playerFactory: (android.content.Context) -> ExoPlayer = { context ->
        ExoPlayer.Builder(context).build()
    }

    // 主线程调度器（internal 便于测试注入 TestDispatcher）。
    // ExoPlayer 必须在有 Looper 的线程（通常是主线程）上创建和操作，
    // 在 Dispatchers.IO 上创建会抛 "Player is accessed on the wrong thread"
    // 并被 catch 静默吞掉，导致检测到了开播但完全无声。
    internal var mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main

    // 提醒链路的独立 Job：onDestroy 时同步取消（不依赖主线程调度是否通畅），
    // 保证 10 秒自动停止定时器随服务销毁立即取消。
    // （测试视角：不取消的话，挂起的定时器会把整个 Robolectric sandbox 钉到 OOM）
    private val alertScopeJob = SupervisorJob()
    private val alertScope: CoroutineScope
        get() = CoroutineScope(mainDispatcher + alertScopeJob)

    private fun playAlertSound() {
        alertScope.launch {
            try {
                // 开播提醒与活动提醒同一周期撞车时，先取消旧定时器、
                // 再释放上一个未停的播放器，防止旧播放器泄漏成双音轨循环直到进程死亡
                alertStopJob?.cancel()
                alertStopJob = null
                alertPlayer?.let { old ->
                    try {
                        if (old.isPlaying) old.stop()
                        old.release()
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "release stale alert player failed", e)
                    }
                    alertPlayer = null
                }

                val player = playerFactory(this@LiveCheckService)
                val attrs = Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_ALARM)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                    .build()
                player.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                if (!alertSoundProvider.setupDataSource(
                        this@LiveCheckService, player, preferenceManager.getAlertSoundUri()
                    )) {
                    AppLogger.w(TAG, "all sound sources failed, skip alert")
                    player.release()
                    return@launch
                }
                player.repeatMode = Player.REPEAT_MODE_ONE  // gapless 循环
                player.playWhenReady = true
                // 异步事件监听：准备/起播/异步加载失败
                // 此前完全无声无日志，排障只能靠 dumpsys audio 外部观察
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) AppLogger.d(TAG, "alert playback started")
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        AppLogger.e(TAG, "alert playback error: ${error.errorCodeName}", error)
                    }
                })
                alertPlayer = player
                lastAlertPlayer = player

                // 10秒后停止。身份校验：若期间来了新提醒换了新播放器，
                // 旧定时器不能误杀新播放器
                alertStopJob = alertScope.launch {
                    delay(10000)
                    if (alertPlayer === player) {
                        stopAlertSound()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "playAlertSound failed", e)
            }
        }
    }

    // 10 秒自动停止的协程句柄，stopAlertSound/新提醒到来时取消，
    // 防止陈旧定时器在服务停止后误触发（或误杀后来的新播放器）
    private var alertStopJob: Job? = null

    // 停止提醒铃声（停止监控/10秒自动停止 共用）。
    // 切到主线程：ExoPlayer 的 stop/release 必须在创建它的线程上调用。
    internal fun stopAlertSound() {
        alertScope.launch { stopAlertSoundSync() }
    }

    // 同步停铃，调用方必须已在播放器创建线程（主线程）上。
    // onDestroy 必须走这里而不是 stopAlertSound()：后者只是把 stop/release
    // 投递到 alertScope 队列，协程体要等 onDestroy 返回后才有机会执行，
    // 而紧随的 alertScopeJob.cancel() 会先把投递杀掉——停止逻辑永远跑不到
    // （真机测试抓包：服务销毁后旧播放器 isPlaying 仍为 true，循环到进程死亡）
    private fun stopAlertSoundSync() {
        alertStopJob?.cancel()
        alertStopJob = null
        alertPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
                AppLogger.w(TAG, "stop alert sound failed", e)
            }
            try {
                it.release()
            } catch (e: Exception) {
                AppLogger.w(TAG, "release alert player failed", e)
            }
        }
        alertPlayer = null
        lastAlertPlayer = null
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
        val currentGeneration = preferenceManager.getMonitoringGeneration()
        val ownsCurrentSession = monitoringGeneration == currentGeneration
        val userStoppedCurrentSession = stopRequestedByUser
            && stopRequestedGeneration == currentGeneration
        AppLogger.d(
            TAG,
            "onDestroy userStop=$userStoppedCurrentSession ownsCurrent=$ownsCurrentSession " +
                "serviceGen=$monitoringGeneration currentGen=$currentGeneration"
        )
        serviceScope.cancel()
        if (ownsCurrentSession) {
            isRunning = false
            lastLiveStatus = false
        }
        // 停止提醒铃声（用户停止监控/服务销毁时铃声必须停）。
        // onDestroy 跑在主线程，直接同步停——投递到 alertScope 会被下面的 cancel 杀掉
        stopAlertSoundSync()
        // 同步取消提醒链路 Job（不依赖主线程调度）：10 秒定时器立即失效
        alertScopeJob.cancel()
        // 只有用户主动停止才清除运行标记；系统杀进程/异常销毁要保留 true，
        // 否则 onDestroy→ServiceRestartReceiver 重启链会被自己卡死
        // （Receiver 启动前检查 prefs，false 会拒绝重启）
        if (userStoppedCurrentSession) {
            preferenceManager.setServiceRunning(false)
        }
        // 旧会话在用户快速 stop→start 后销毁时，绝不能取消新会话刚排好的任务。
        if (ownsCurrentSession) {
            cancelScheduledChecks(this)
        }

        // 释放所有WakeLock
        if (::checkWakeLock.isInitialized && checkWakeLock.isHeld) {
            checkWakeLock.release()
        }

        // 只有非用户手动停止时才发送广播重启服务
        if (!userStoppedCurrentSession && ownsCurrentSession && preferenceManager.isServiceRunning()) {
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
        stopRequestedByUser = false
        stopRequestedGeneration = NO_MONITORING_GENERATION
    }

    private fun scheduleNextCheckAlarm() {
        try {
            val intent = Intent(this, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = System.currentTimeMillis() +
                preferenceManager.getCheckIntervalSeconds() * 1000L
            com.bilibili.livemonitor.util.AlarmScheduler.schedule(
                this, triggerAt, pendingIntent, "scheduleNextCheckAlarm"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "scheduleNextCheckAlarm failed", e)
        }
    }

    // 常规检查只确保活动 Alarm 存在；真正的 5 分钟周期只在 ACTION_CHECK_DYNAMICS
    // 完成后重排，避免每分钟直播检查把触发时间不断往后推。
    private fun ensureDynamicAlarmScheduled() {
        if (!isActivityMonitoringEnabled()) {
            cancelDynamicAlarm()
            return
        }
        val existing = PendingIntent.getService(
            this,
            DYNAMIC_ALARM_REQUEST_CODE,
            Intent(this, LiveCheckService::class.java).apply { action = ACTION_CHECK_DYNAMICS },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing == null) scheduleNextDynamicAlarm()
    }

    // 动态流独立 5min Alarm（风控脆弱，降频 + ±10s 抖动避免固定间隔被识别）
    private fun scheduleNextDynamicAlarm() {
        if (!isActivityMonitoringEnabled()) {
            cancelDynamicAlarm()
            return
        }
        try {
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
            com.bilibili.livemonitor.util.AlarmScheduler.schedule(
                this, triggerAt, pendingIntent, "scheduleNextDynamicAlarm"
            )
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
            pendingIntent.cancel()
        } catch (e: Exception) {
            AppLogger.e(TAG, "cancelDynamicAlarm failed", e)
        }
    }

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val ACTION_STOP_SERVICE = "com.bilibili.livemonitor.STOP_SERVICE"
        const val ACTION_STOP_ALERT = "com.bilibili.livemonitor.STOP_ALERT"
        const val ACTION_WATCH_LIVE = "com.bilibili.livemonitor.WATCH_LIVE"
        const val ACTION_RESTART_SERVICE = "com.bilibili.livemonitor.RESTART_SERVICE"
        const val ACTION_CHECK_DYNAMICS = "com.bilibili.livemonitor.CHECK_DYNAMICS"
        const val EXTRA_MONITORING_GENERATION = "monitoring_generation"
        private const val DEFAULT_ROOM_ID = com.bilibili.livemonitor.util.BiliTargets.ROOM_ID
        private const val NO_MONITORING_GENERATION = -1L
        private const val DYNAMIC_CHECK_INTERVAL = 5 * 60_000L // 5分钟
        private const val CHECK_TIMEOUT = 25_000L // 单次检测超时25秒
        private const val ERROR_RETRY_DELAY = 15_000L // 错误后15秒重试
        private const val CHECK_WAKE_LOCK_TIMEOUT = 90_000L // 检测+重试全程锁（25s+15s+25s+余量）
        private const val TITLE_CHANGE_MIN_LIVE_MS = 5 * 60_000L // 开播至少 5 分钟后的标题变化才提醒
        private const val STATUS_RESTORE_MAX_AGE = 600_000L // 进程重启时恢复状态的新鲜度窗口（10分钟）
        private const val ALARM_REQUEST_CODE = 2001
        private const val DYNAMIC_ALARM_REQUEST_CODE = 2002
        private const val TAG = "LiveCheckService"

        @Volatile
        var isRunning = false

        @Volatile
        var lastLiveStatus = false

        // androidTest 钩子（同进程 instrumented test 专用，生产恒为 null）：
        // apiOverride 注入 fake 检测源；lastAlertPlayer 观测真实提醒播放器
        @Volatile
        internal var apiOverride: LiveStatusChecker? = null

        @Volatile
        internal var lastAlertPlayer: ExoPlayer? = null

        /** Stop path used when no service instance exists; avoids creating a service only for STOP. */
        fun cancelScheduledChecks(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val checkIntent = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    Intent(context, AlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(checkIntent)
                checkIntent.cancel()
                val activityIntent = PendingIntent.getService(
                    context,
                    DYNAMIC_ALARM_REQUEST_CODE,
                    Intent(context, LiveCheckService::class.java).apply { action = ACTION_CHECK_DYNAMICS },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(activityIntent)
                activityIntent.cancel()
            } catch (e: Exception) {
                AppLogger.e(TAG, "cancelScheduledChecks failed", e)
            }
        }
    }
}
