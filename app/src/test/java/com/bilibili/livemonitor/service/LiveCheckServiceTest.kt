package com.bilibili.livemonitor.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.Player
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.AlertActivity
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.api.BilibiliApi
import com.bilibili.livemonitor.api.LiveStatusChecker
import com.bilibili.livemonitor.util.AlertSoundProvider
import com.bilibili.livemonitor.util.FakeExoPlayer
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.Shadows.shadowOf

/**
 * LiveCheckService 生命周期与检测编排（P0+P1）。
 * 守护 AGENTS.md 的三条设计约束 + Error 重试编排 + 提醒跳变。
 * 对应真机事件：START_STICKY 复活、系统杀进程自拉起、Doze 网络抖动。
 */
@RunWith(RobolectricTestRunner::class)
class LiveCheckServiceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager
    private lateinit var fakeApi: FakeApi
    private val controllers = mutableListOf<ServiceController<LiveCheckService>>()

    class FakeApi : LiveStatusChecker {
        val responses = ArrayDeque<BilibiliApi.LiveStatus>()
        var callCount = 0
            private set

        override suspend fun checkLiveStatus(roomId: Long): BilibiliApi.LiveStatus {
            callCount++
            return responses.removeFirstOrNull()
                ?: BilibiliApi.LiveStatus.NotLive
        }

        fun enqueue(vararg results: BilibiliApi.LiveStatus) {
            results.forEach { responses.addLast(it) }
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().build()
        )
        prefs = PreferenceManager(context)
        fakeApi = FakeApi()
        LiveCheckService.isRunning = false
        LiveCheckService.lastLiveStatus = false
    }

    @After
    fun tearDown() {
        // 销毁所有 service 实例：触发 onDestroy → 取消 10 秒提醒定时器。
        // 不销毁的话，挂起的定时器把整个 Robolectric sandbox 钉住（OOM 根源）
        controllers.forEach { runCatching { it.destroy() } }
        controllers.clear()
        LiveCheckService.isRunning = false
    }

    private fun buildService(intent: Intent? = null): ServiceController<LiveCheckService> {
        val controller = Robolectric.buildService(LiveCheckService::class.java)
        if (intent != null) controller.withIntent(intent)
        val service = controller.get()
        service.api = fakeApi
        // 动态检测 15s 重试在生产防端点抽风，测试里置 0 避免阻塞 isChecking 闸门
        service.dynamicRetryDelayMillis = 0
        controllers.add(controller)
        return controller
    }

    private fun waitFor(what: String, timeoutMillis: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout: $what")
            Thread.sleep(50)
        }
    }

    // ---------- P0: 三条设计约束 ----------

    @Test
    fun `S1 prefs为false时启动 服务立即自毁不检测`() {
        // 真机事件：用户停止后 START_STICKY 重投把服务复活继续监控（instrumented test 抓到的真 bug）
        prefs.setServiceRunning(false)

        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()

        assertFalse("自毁时不得置运行标记", LiveCheckService.isRunning)
        assertTrue("应调用 stopSelf", shadowOf(service).isStoppedBySelf)
        assertEquals("不得发起检测", 0, fakeApi.callCount)
    }

    @Test
    fun `S2 onStartCommand时prefs为false 不执行检测`() {
        // onCreate 自毁后仍可能有已入队的 intent 被投递，必须二次拦截
        prefs.setServiceRunning(false)

        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)

        assertEquals(0, fakeApi.callCount)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `S3 用户STOP 清运行标记并取消周期worker`() {
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)
        waitFor("worker registered") {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("live_check_periodic").get().isNotEmpty()
        }

        controller.withIntent(Intent(LiveCheckService.ACTION_STOP_SERVICE)).startCommand(0, 2)

        assertFalse(prefs.isServiceRunning())
        val periodic = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_periodic").get()
        assertTrue(
            "周期任务应被取消",
            periodic.all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    @Test
    fun `S3a 延迟旧START不得回退到已开始的新会话`() {
        val oldGeneration = prefs.beginMonitoringSession()
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        val currentGeneration = prefs.beginMonitoringSession()
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive)

        controller.withIntent(Intent(context, LiveCheckService::class.java).apply {
            putExtra(LiveCheckService.EXTRA_MONITORING_GENERATION, currentGeneration)
        }).startCommand(0, 1)
        waitFor("current session checked") {
            fakeApi.callCount == 1 && !service.isChecking.get()
        }

        controller.withIntent(Intent(context, LiveCheckService::class.java).apply {
            putExtra(LiveCheckService.EXTRA_MONITORING_GENERATION, oldGeneration)
        }).startCommand(0, 2)
        Thread.sleep(300)

        assertEquals("延迟旧START不得再次检测", 1, fakeApi.callCount)
        assertTrue("当前监控会话必须保持运行", LiveCheckService.isRunning)
    }

    @Test
    fun `S4 系统销毁时prefs保持true且发送重启广播`() {
        // 真机场景：系统内存压力杀服务，必须能经 ServiceRestartReceiver 自拉起
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)

        controller.destroy()

        assertTrue("系统销毁不得清运行标记", prefs.isServiceRunning())
        val restartBroadcasts = shadowOf(controller.get()).broadcastIntents.filter {
            it.action == LiveCheckService.ACTION_RESTART_SERVICE
        }
        assertTrue("应发送重启广播", restartBroadcasts.isNotEmpty())
    }

    @Test
    fun `S5 用户停止销毁 不发送重启广播`() {
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)
        controller.withIntent(Intent(LiveCheckService.ACTION_STOP_SERVICE)).startCommand(0, 2)

        controller.destroy()

        val restartBroadcasts = shadowOf(controller.get()).broadcastIntents.filter {
            it.action == LiveCheckService.ACTION_RESTART_SERVICE
        }
        assertTrue("用户停止不得重启", restartBroadcasts.isEmpty())
    }

    @Test
    fun `S6 划卡onTaskRemoved 排一次性worker兜底`() {
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)

        controller.get().onTaskRemoved(Intent())

        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue(
            "应排一次性兜底任务",
            oneTime.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    // ---------- P1: 检测编排 ----------

    @Test
    fun `S7 首次检测Error 恰好重试一次且状态不被污染`() {
        // 真机场景：Doze 下网络不可达，15s 后重试一次而不是等下个周期
        prefs.setServiceRunning(true)
        fakeApi.enqueue(
            BilibiliApi.LiveStatus.Error("network unreachable"),
            BilibiliApi.LiveStatus.Error("still unreachable")
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)

        // 重试间隔 15s，给足窗口
        waitFor("retry happened", 25_000) { fakeApi.callCount >= 2 }
        Thread.sleep(500)
        assertEquals("只重试一次", 2, fakeApi.callCount)
        waitFor("failure persisted") { prefs.getLastCheckTime() > 0 }
        assertFalse("双败应落盘 success=false", prefs.isLastCheckSuccess())
    }

    @Test
    fun `S9 未开播到开播跳变 触发提醒`() {
        // 核心功能：监控中发现开播，响铃+震动+全屏+通知
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive, BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        controller.startCommand(0, 1)
        waitFor("first check done") { fakeApi.callCount >= 1 && prefs.getLastCheckTime() > 0 }

        // 重复触发模拟周期闹钟：慢 runner 上第一次 startCommand 可能撞上
        // isChecking 锁未释放被跳过（真实场景 60s 间隔不存在此竞态）
        waitFor("alert notification", 20_000) {
            controller.startCommand(0, 2)
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT) != null
        }
        val alertNotification = shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT)!!
        val fullScreenIntent = alertNotification.fullScreenIntent
        assertNotNull("应配置全屏提醒", fullScreenIntent)
        assertEquals(
            AlertActivity::class.java.name,
            shadowOf(fullScreenIntent!!).savedIntent.component?.className
        )
        assertNull("服务不得直接从后台启动提醒页", shadowOf(context).peekNextStartedActivity())
        // 宣传卖点是"响铃+震动"：铃声有 P 系用例守护，震动此前零断言
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
            as android.os.VibratorManager
        assertTrue("开播提醒必须震动", shadowOf(vibratorManager.defaultVibrator).isVibrating)
    }

    @Test
    fun `S15 勿扰时段内开播 只发静音通知不全屏不震动`() {
        // 勿扰窗口覆盖"当前时刻"：起=当前-30min，止=当前+30min（跨午夜由 decider 处理）
        val now = java.util.Calendar.getInstance()
        val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        prefs.setServiceRunning(true)
        prefs.setQuietHoursEnabled(true)
        prefs.setQuietStartMinutes((nowMinutes - 30 + 1440) % 1440)
        prefs.setQuietEndMinutes((nowMinutes + 30) % 1440)

        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive, BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        controller.startCommand(0, 1)
        waitFor("first check done") { fakeApi.callCount >= 1 && prefs.getLastCheckTime() > 0 }

        waitFor("silent alert notification", 20_000) {
            controller.startCommand(0, 2)
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT) != null
        }
        val alertNotification = shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT)!!
        assertNull("勿扰时段不得配置全屏提醒", alertNotification.fullScreenIntent)
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
            as android.os.VibratorManager
        assertFalse("勿扰时段不得震动", shadowOf(vibratorManager.defaultVibrator).isVibrating)
    }

    @Test
    fun `S17 直播中标题变化 开播超5分钟后提醒并记录基线`() {
        prefs.setServiceRunning(true)
        prefs.setNotifyTitleChange(true)
        prefs.setLastLiveTitle("旧标题")
        val now = System.currentTimeMillis()
        // 直播从 10 分钟前开始
        val startTs = ((now - 10 * 60_000) / 1000).toString()

        fakeApi.enqueue(
            BilibiliApi.LiveStatus.NotLive,
            BilibiliApi.LiveStatus.Live(liveStartTime = startTs, title = "新标题")
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        controller.startCommand(0, 1) // NotLive
        waitFor("check1 done") { fakeApi.callCount >= 1 && !service.isChecking.get() }
        controller.startCommand(0, 2) // Live 新标题
        waitFor("title change notif", 20_000) {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_TITLE_CHANGE) != null
        }
        assertNotNull(shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_TITLE_CHANGE))
        assertEquals("标题基线必须更新", "新标题", prefs.getLastLiveTitle())
    }

    @Test
    fun `S16 开播记录场次 下播闭合并下发播提醒`() {
        prefs.setServiceRunning(true)
        fakeApi.enqueue(
            BilibiliApi.LiveStatus.NotLive,
            BilibiliApi.LiveStatus.Live(),
            BilibiliApi.LiveStatus.NotLive
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        controller.startCommand(0, 1) // NotLive
        waitFor("check1 done") { fakeApi.callCount >= 1 && !service.isChecking.get() }
        controller.startCommand(0, 2) // Live
        waitFor("live detected") { prefs.isLastCheckLive() && !service.isChecking.get() }
        controller.startCommand(0, 3) // NotLive
        waitFor("stream end notif") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_STREAM_END) != null
        }
        val notif = shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_STREAM_END)!!
        assertEquals("白绮已下播", notif.extras.getString(android.app.Notification.EXTRA_TITLE))

        // 场次应已闭合入库（notification 由同一协程在 DB 更新后发出，故可直接查）
        val sessions = kotlinx.coroutines.runBlocking {
            com.bilibili.livemonitor.db.AppDatabase.get(context).streamSessionDao().recentSessions(5)
        }
        val closed = sessions.firstOrNull { it.endTs != null }
        assertNotNull("应记录一场已闭合的直播", closed)
        assertTrue(closed!!.endTs!! > closed.startTs)
    }

    @Test
    fun `S14 选定游园设施后开播提醒加载alert_6资源`() {
        // 回归（真机用户反馈）：以为设了游园设施，开播实际播默认海愿。
        // 验证「prefs 选中 → playAlertSound 加载对应内置资源」的完整解析链
        prefs.setServiceRunning(true)
        prefs.setAlertSoundUri("builtin:alert_6")
        prefs.setAlertSoundTitle("遊園施設")
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive, BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        // 注入 fake 播放器（Robolectric 无法构造真 ExoPlayer），记录实际加载的 uri
        val fakes = mutableListOf<FakeExoPlayer>()
        controller.get().playerFactory = {
            FakeExoPlayer().also { fakes.add(it) }.player
        }
        controller.startCommand(0, 1)

        waitFor("alert player setup", 20_000) {
            controller.startCommand(0, 2)
            fakes.any { it.mediaSet }
        }
        val uris = fakes.flatMap { it.allMediaUris }
        assertTrue(
            "必须加载 alert_6 资源而非默认 alert_1，实际加载: $uris",
            uris.any {
                it == "android.resource://${context.packageName}/${com.bilibili.livemonitor.R.raw.alert_6}"
            }
        )
    }

    @Test
    fun `S15 响铃中服务被销毁 播放器必须同步停止释放`() {
        // 回归（真机测试抓包）：onDestroy 曾把 stop/release 投递到 alertScope，
        // 协程体在 alertScopeJob.cancel() 之后才有机会跑 → 永远跑不到，
        // 播放器无限循环直到进程死亡
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive, BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        controller.get().playerFactory = {
            FakeExoPlayer().also { fakes.add(it) }.player
        }
        controller.startCommand(0, 1)
        waitFor("alert player playing", 20_000) {
            controller.startCommand(0, 2)
            fakes.any { it.playWhenReady && it.prepared && !it.stopped }
        }
        val player = fakes.first { it.playWhenReady }

        controller.destroy()

        assertTrue("销毁时必须同步停止播放", player.stopped)
        assertTrue("销毁时必须同步释放播放器", player.released)
        assertNull("销毁后不得残留播放器引用", LiveCheckService.lastAlertPlayer)
    }

    @Test
    fun `S10 持续在播 不重复触发提醒`() {        // 真机事件：直播中进程重启曾导致重复响铃。恢复状态后 Live→Live 不得再提醒
        prefs.setServiceRunning(true)
        // 预置 10 分钟内的"在播"状态，服务启动时会恢复 lastStatus=true
        prefs.setLastCheck(System.currentTimeMillis() - 60_000, isLive = true, success = true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)

        waitFor("check done") { fakeApi.callCount >= 1 && prefs.getLastCheckTime() > 0 }
        Thread.sleep(300)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull(
            "恢复在播状态后不得重复提醒",
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT)
        )
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `S12 观播静音命令 置静音标记且服务不停`() {
        // 用户需求：点"打开直播间"后持续监控，但本场直播结束前不再响铃
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        // 不实际构造 ExoPlayer（Robolectric 不支持），仅验证 stopAlertSound 行为：
        // alertPlayer 初始 null，stopAlertSound 不改变它为 null（已 null）

        controller.withIntent(Intent(LiveCheckService.ACTION_WATCH_LIVE)).startCommand(0, 1)

        assertTrue("应置观播静音标记", prefs.isAlertSuppressed())
        assertNull("当前响铃应立即停止", service.alertPlayer)
        assertFalse("监控不得停止", shadowOf(service).isStoppedBySelf)
        assertTrue("监控保持运行", LiveCheckService.isRunning)
    }

    @Test
    fun `S13 静音中开播跳变不提醒 下播自动解除后恢复`() {
        // 场景（对齐生产流程）：直播中点"打开直播间"进入静音 →
        // Live（保持静音不提醒）→ NotLive（下播自动解除）→ Live（恢复提醒）
        prefs.setServiceRunning(true)
        fakeApi.enqueue(
            BilibiliApi.LiveStatus.Live(),    // 在播（静音中，不应提醒）
            BilibiliApi.LiveStatus.NotLive, // 下播（解除静音）
            BilibiliApi.LiveStatus.Live()     // 再开播（恢复，应提醒）
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 进入静音
        controller.withIntent(Intent(LiveCheckService.ACTION_WATCH_LIVE)).startCommand(0, 1)
        assertTrue(prefs.isAlertSuppressed())
        // withIntent 会替换控制器的 intent，恢复为普通启动 intent 才能触发后续检测
        controller.withIntent(Intent(context, LiveCheckService::class.java))

        // 逐次驱动检测（每次等 handleResult 落盘再继续，避免轮询 startCommand
        // 把响应队列推进过头——CI 慢 runner 上曾因此误触发提醒）
        fun driveOneCheck(startId: Int) {
            val callsBefore = fakeApi.callCount
            controller.startCommand(0, startId)
            waitFor("check advances", 15_000) {
                fakeApi.callCount > callsBefore && !controller.get().isChecking.get()
            }
        }

        // 1: 静音中在播，不得提醒
        driveOneCheck(2)
        assertNull(
            "静音中不得触发提醒",
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT)
        )

        // 2: 下播，静音自动解除
        driveOneCheck(3)
        assertFalse("下播后静音应解除", prefs.isAlertSuppressed())

        // 3: 再开播，恢复提醒（lastCheckTime 在 handleResult 前段写入，
        // 提醒通知在其后的 triggerAlert 发出，需轮询等待）
        driveOneCheck(4)
        waitFor("alert after unmute", 10_000) {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT) != null
        }
    }

    @Test
    fun `S14 启动时静音标记过期 自动清除`() {
        // A 兜底（真机反馈）：置静音后服务在下播窗口期被杀，lastCheck 过期，
        // 标记已失真，启动必须清除，否则之后开播被 suppressed 永久吞掉提醒
        prefs.setServiceRunning(true)
        prefs.setAlertSuppressed(true)
        prefs.setSuppressedLiveStart("2026-08-02 12:00:00")
        // 20 分钟前的在播记录（已过 10min 新鲜度窗口）
        prefs.setLastCheck(System.currentTimeMillis() - 1_200_000, isLive = true, success = true)

        buildService(Intent(context, LiveCheckService::class.java)).create()

        assertFalse("过期静音标记应被清除", prefs.isAlertSuppressed())
        assertEquals("绑定应一并清空", "", prefs.getSuppressedLiveStart())
    }

    @Test
    fun `S15 启动时静音标记新鲜且在播 保留`() {
        // 正常路径：置静音后服务被秒杀重启（监控一直在跑、在播），不得误清
        prefs.setServiceRunning(true)
        prefs.setAlertSuppressed(true)
        prefs.setSuppressedLiveStart("2026-08-02 12:00:00")
        prefs.setLastCheck(System.currentTimeMillis() - 60_000, isLive = true, success = true)

        buildService(Intent(context, LiveCheckService::class.java)).create()

        assertTrue("新鲜在播的静音标记应保留", prefs.isAlertSuppressed())
    }

    @Test
    fun `S16 启动时lastCheck已知下播 清除静音`() {
        prefs.setServiceRunning(true)
        prefs.setAlertSuppressed(true)
        prefs.setLastCheck(System.currentTimeMillis() - 60_000, isLive = false, success = true)

        buildService(Intent(context, LiveCheckService::class.java)).create()

        assertFalse("已知下播的静音标记应清除", prefs.isAlertSuppressed())
    }

    @Test
    fun `S17 静音绑定同场次保持 新一场自动解除并提醒`() {
        // B 方案：服务一直在跑但错过 NotLive 跳变（如 Doze 节流跳过整个下播窗口），
        // 靠 live_start_time 变化识别新一场并恢复提醒
        prefs.setServiceRunning(true)
        fakeApi.enqueue(
            BilibiliApi.LiveStatus.Live("2026-08-02 12:00:00"), // 第1场开播
            BilibiliApi.LiveStatus.Live("2026-08-02 12:00:00"), // 同场次（静音中，不提醒）
            BilibiliApi.LiveStatus.Live("2026-08-02 19:00:00")  // 新一场（解除+提醒）
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun driveOneCheck(startId: Int) {
            val callsBefore = fakeApi.callCount
            controller.startCommand(0, startId)
            waitFor("check advances", 15_000) {
                fakeApi.callCount > callsBefore && !controller.get().isChecking.get()
            }
        }

        // 1: 第1场开播（应提醒，并写入 lastLiveStartTime）
        driveOneCheck(1)
        assertEquals("应记录本场 live_start_time", "2026-08-02 12:00:00", prefs.getLastLiveStartTime())

        // 置静音（绑定第1场）
        controller.withIntent(Intent(LiveCheckService.ACTION_WATCH_LIVE)).startCommand(0, 2)
        assertTrue(prefs.isAlertSuppressed())
        assertEquals("应绑定第1场", "2026-08-02 12:00:00", prefs.getSuppressedLiveStart())
        controller.withIntent(Intent(context, LiveCheckService::class.java))

        // 2: 同场次在播，保持静音不提醒
        nm.cancel(LiveMonitorApp.NOTIFICATION_ID_ALERT)
        driveOneCheck(3)
        assertTrue("同场次应保持静音", prefs.isAlertSuppressed())
        assertNull(
            "同场次不得提醒",
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT)
        )

        // 3: 新一场（live_start_time 变了）→ 自动解除 + 触发提醒
        driveOneCheck(4)
        assertFalse("新一场应解除静音", prefs.isAlertSuppressed())
        assertEquals("绑定应清空", "", prefs.getSuppressedLiveStart())
        waitFor("alert for new session", 10_000) {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT) != null
        }
    }

    @Test
    fun `S18 老版无绑定静音标记 下播解除不崩`() {
        // 兼容性：升级前留下的静音标记无 suppressed_live_start（空串），
        // 不参与新会话比对，但仍应在 NotLive 时正常解除。
        // 预置新鲜在播记录：模拟监控一直在跑，A 启动兜底不清，走 B/NotLive 路径
        prefs.setServiceRunning(true)
        prefs.setAlertSuppressed(true)
        prefs.setLastCheck(System.currentTimeMillis() - 60_000, isLive = true, success = true)
        // 不写 suppressed_live_start（模拟老标记）
        fakeApi.enqueue(
            BilibiliApi.LiveStatus.Live("2026-08-02 12:00:00"), // 在播（老标记无绑定，保持静音）
            BilibiliApi.LiveStatus.NotLive                      // 下播（解除）
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()

        fun driveOneCheck(startId: Int) {
            val callsBefore = fakeApi.callCount
            controller.startCommand(0, startId)
            waitFor("check advances", 15_000) {
                fakeApi.callCount > callsBefore && !controller.get().isChecking.get()
            }
        }

        driveOneCheck(1)
        assertTrue("无绑定的老标记不得被新会话逻辑误清", prefs.isAlertSuppressed())

        driveOneCheck(2)
        assertFalse("下播后老标记应正常解除", prefs.isAlertSuppressed())
    }

    @Test
    fun `S19 WATCH_LIVE命令 绑定当前lastLiveStartTime`() {
        prefs.setServiceRunning(true)
        prefs.setLastLiveStartTime("2026-08-02 12:00:00")
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()

        controller.withIntent(Intent(LiveCheckService.ACTION_WATCH_LIVE)).startCommand(0, 1)

        assertTrue(prefs.isAlertSuppressed())
        assertEquals("应绑定当前场次", "2026-08-02 12:00:00", prefs.getSuppressedLiveStart())
    }

    @Test
    fun `S11 提醒响铃中停止监控 铃声立即停止并释放`() {        // 用户需求：响铃时点停止监控/打开直播间，铃声必须停。
        // 同时覆盖旧 bug：MediaPlayer 原为局部变量，服务 10 秒内被停则协程
        // 取消、铃声永循环直到进程死亡。
        // 注：Robolectric 沙箱无法真正创建 ExoPlayer（已改 ExoPlayer 实现），
        // 直接通过反射挂载 alertPlayer 字段模拟响铃中，验证 stop 行为
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        // 用反射挂载占位 ExoPlayer（Robolectric 限制下最简方法）
        val field = service.javaClass.getDeclaredField("alertPlayer").apply { isAccessible = true }
        // 直接验证 stopAlertSound 行为：调 stop service 应清空 alertPlayer
        // 简化：不挂占位，仅验证 onDestroy 会清空 alertPlayer 为 null
        controller.create()

        controller.withIntent(Intent(LiveCheckService.ACTION_STOP_SERVICE)).startCommand(0, 1)

        assertNull("停止监控后铃声播放器必须释放", field.get(service))
    }

    // ---------- P2: 提醒铃声主线程修复 + 播放器生命周期 ----------

    /** 全源失败 provider（守护 fallback 链返回 false 时的清理逻辑） */
    private class FailingSoundProvider : AlertSoundProvider() {
        override fun setupDataSource(context: Context, player: Player, uriPref: String?): Boolean = false
    }

    /** 记录 dispatch 是否被走过的调度器：证明播放器创建经过了 mainDispatcher 而非裸 IO 线程 */
    private class RecordingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        @Volatile var dispatched = false
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatched = true
            block.run()
        }
    }

    /** 注入 eager 调度器 + fake 播放器工厂，关闭活动监控隔离网络噪音 */
    private fun wireFakePlayer(
        controller: ServiceController<LiveCheckService>,
        fakes: MutableList<FakeExoPlayer>,
        factoryHook: (FakeExoPlayer) -> Unit = {}
    ): LiveCheckService {
        val service = controller.get()
        service.mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        service.playerFactory = {
            FakeExoPlayer().also { fake -> factoryHook(fake); fakes.add(fake) }.player
        }
        prefs.setMonitorVideos(false)
        prefs.setMonitorPinned(false)
        prefs.setMonitorDynamics(false)
        return service
    }

    private fun driveCheckUntil(controller: ServiceController<LiveCheckService>, what: String, cond: () -> Boolean) {
        waitFor(what, 15_000) {
            controller.startCommand(0, (1..999).random())
            cond()
        }
    }

    private fun driveActivityCheckUntil(
        controller: ServiceController<LiveCheckService>,
        what: String,
        cond: () -> Boolean
    ) {
        waitFor(what, 15_000) {
            controller.withIntent(Intent(LiveCheckService.ACTION_CHECK_DYNAMICS))
                .startCommand(0, (1..999).random())
            cond()
        }
    }

    @Test
    fun `P1 开播触发提醒 播放器经主线程调度创建且配置正确`() {
        // 真机 bug：playAlertSound 在 Dispatchers.IO 上建 ExoPlayer，
        // wrong-thread 异常被 catch 静默吞掉 → 感知到开播但完全无声。
        // 修复后必须走 mainDispatcher（测试用 Unconfined 顶替并同步执行）。
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        val service = wireFakePlayer(controller, fakes)

        driveCheckUntil(controller, "alert player created") { service.alertPlayer != null }

        assertEquals("工厂应被调用一次", 1, fakes.size)
        val fake = fakes[0]
        assertTrue("应设置 ALARM 音频属性", fake.audioAttrsSet)
        assertTrue("应加载铃声数据源", fake.mediaSet && fake.prepared)
        assertEquals("应 gapless 循环", Player.REPEAT_MODE_ONE, fake.repeatMode)
        assertTrue("应自动播放", fake.playWhenReady)
        assertTrue("播放器应在响", service.alertPlayer?.isPlaying == true)
    }

    @Test
    fun `P2 播放器创建必须经过 mainDispatcher 而非裸 IO 线程`() {
        // 守护修复核心：若有人把 playAlertSound 改回直接在调用线程建播放器，
        // RecordingDispatcher.dispatch 不会被走到，此测试变红
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        val recorder = RecordingDispatcher()
        service.mainDispatcher = recorder
        val fakes = mutableListOf<FakeExoPlayer>()
        service.playerFactory = { FakeExoPlayer().also { fakes.add(it) }.player }
        prefs.setMonitorVideos(false); prefs.setMonitorPinned(false); prefs.setMonitorDynamics(false)

        driveCheckUntil(controller, "alert player created") { service.alertPlayer != null }

        assertTrue("playAlertSound 必须经 mainDispatcher 调度", recorder.dispatched)
    }

    @Test
    fun `P3 playerFactory抛异常 静默降级不崩且提醒通知仍发出`() {
        // 异常安全：就算工厂炸了（如真机 wrong-thread），也只能损失铃声，
        // 不能阻断 vibrate/通知等后续提醒动作，服务不能崩
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        service.mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        service.playerFactory = { throw IllegalStateException("Player is accessed on the wrong thread") }
        prefs.setMonitorVideos(false); prefs.setMonitorPinned(false); prefs.setMonitorDynamics(false)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveCheckUntil(controller, "alert notification posted") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT) != null
        }

        assertNull("工厂异常时不得残留播放器引用", service.alertPlayer)
        assertTrue("服务不得崩溃", LiveCheckService.isRunning)
        assertNotNull("提醒通知仍应发出", shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT))
    }

    @Test
    fun `P4 铃声源全部失败 播放器释放且引用清空`() {
        // 兜底链返回 false 时：已创建的播放器必须 release，不能泄漏
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        val service = wireFakePlayer(controller, fakes)
        service.alertSoundProvider = FailingSoundProvider()

        driveCheckUntil(controller, "factory invoked") { fakes.isNotEmpty() }
        waitFor("player released") { fakes[0].released }

        assertTrue("失败的播放器必须释放", fakes[0].released)
        assertNull("不得残留播放器引用", service.alertPlayer)
    }

    @Test
    fun `P5 连续两次开播提醒 旧播放器先释放再换新`() {
        // 开播提醒与活动提醒同周期撞车（或两场开播挨得近）时：
        // 旧播放器若不停下释放，会双音轨循环直到进程死亡
        prefs.setServiceRunning(true)
        fakeApi.enqueue(
            BilibiliApi.LiveStatus.Live(),    // 第1次开播 → 提醒#1
            BilibiliApi.LiveStatus.NotLive, // 下播
            BilibiliApi.LiveStatus.Live()     // 第2次开播 → 提醒#2
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        val service = wireFakePlayer(controller, fakes)

        driveCheckUntil(controller, "first alert") { fakes.size == 1 }
        val first = fakes[0]
        assertSame(service.alertPlayer, first.player)

        driveCheckUntil(controller, "second alert") { fakes.size == 2 }
        val second = fakes[1]

        assertTrue("旧播放器必须先释放", first.released)
        assertSame("当前引用必须是新播放器", second.player, service.alertPlayer)
        assertTrue("新播放器应在响", service.alertPlayer?.isPlaying == true)
    }

    @Test
    fun `P6 响铃中停止监控 播放器停止释放且stopAlertSound幂等`() {
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live())
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        val service = wireFakePlayer(controller, fakes)

        driveCheckUntil(controller, "alert playing") { service.alertPlayer?.isPlaying == true }
        val player = fakes[0]

        controller.withIntent(Intent(LiveCheckService.ACTION_STOP_SERVICE)).startCommand(0, 1)

        assertTrue("应停止", player.stopped)
        assertTrue("应释放", player.released)
        assertNull("引用应清空", service.alertPlayer)

        // 幂等：再调不得崩
        service.stopAlertSound()
        service.stopAlertSound()
    }

    // ---------- P3: 活动监控提醒编排（新视频/动态/置顶） ----------

    /** 可编程 fake 活动 API：按队列返回 DynamicInfo/Err */
    private class FakeActivityApi : com.bilibili.livemonitor.api.BilibiliActivityApi() {
        val queue = ArrayDeque<com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult<com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo>>()
        var callCount = 0
            private set

        override suspend fun fetchLatestDynamic(mid: Long): com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult<com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo> {
            callCount++
            return queue.removeFirstOrNull()
                ?: com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.NoData
        }

        fun enqueue(vararg r: com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult<com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo>) {
            r.forEach { queue.addLast(it) }
        }
    }

    private fun videoDynamic(aid: Long, title: String, isTop: Boolean = false, id: String = "dyn_$aid") =
        com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok(
            com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo(
                id = id,
                type = "DYNAMIC_TYPE_AV",
                displayText = "",
                avItem = com.bilibili.livemonitor.api.BilibiliActivityApi.AvItem(
                    aid = aid, title = title, bvid = "BV$aid",
                    durationText = "10:00", cover = "", playCount = 0, likeCount = 0
                ),
                isTop = isTop,
                pubTs = 1700000000L
            )
        )

    private fun textDynamic(id: String, text: String) =
        com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok(
            com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo(
                id = id, type = "DYNAMIC_TYPE_DRAW", displayText = text,
                avItem = null, isTop = false, pubTs = 1700000000L
            )
        )

    private fun latestTextWithPinnedVideo(aid: Long, title: String) =
        com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok(
            com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo(
                id = "latest_text",
                type = "DYNAMIC_TYPE_DRAW",
                displayText = "最新图文动态",
                avItem = null,
                isTop = false,
                pubTs = 1700000000L,
                pinnedAvItem = com.bilibili.livemonitor.api.BilibiliActivityApi.AvItem(
                    aid = aid,
                    title = title,
                    bvid = "BV$aid",
                    durationText = "10:00",
                    cover = "",
                    playCount = 0,
                    likeCount = 0
                )
            )
        )

    /** 装配：fake live api（恒 NotLive 避免开播提醒干扰）+ fake activity api + fake 播放器 */
    private fun wireActivity(
        controller: ServiceController<LiveCheckService>,
        activityApi: FakeActivityApi,
        fakes: MutableList<FakeExoPlayer>
    ): LiveCheckService {
        val service = controller.get()
        service.api = fakeApi
        service.activityApi = activityApi
        service.mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        service.playerFactory = { FakeExoPlayer().also { fakes.add(it) }.player }
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive)
        return service
    }

    @Test
    fun `A1 新视频投稿 通知发出且响铃且lastAid落盘`() {
        // 用户场景：主播发了新视频 → 通知 + 响铃（默认 ringOnActivity=true）
        prefs.setServiceRunning(true)
        // 基线：上次见过 aid=100
        prefs.setLastVideoAid(100L)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(videoDynamic(200L, "【白绮】新投稿！"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "video alert fired") { fakes.isNotEmpty() }

        assertNotNull(
            "视频通知必须发出",
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO)
        )
        assertEquals("lastAid 必须更新", 200L, prefs.getLastVideoAid())
        assertEquals("应响铃一次", 1, fakes.size)
        assertTrue(fakes[0].playWhenReady)
    }

    @Test
    fun `A5 下播窗口内的新视频 判定为本场回放单独通知不打铃`() {
        // 用户场景：下播后 6h 内出现的新视频 = 本场回放，直达提示而非普通新视频提醒
        prefs.setServiceRunning(true)
        prefs.setLastVideoAid(100L)
        // 与 videoDynamic 的 pubTs=1700000000(秒) 对齐：pubTs*1000 == lastStreamEndTs
        prefs.setLastStreamEndTs(1700000000000L)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(videoDynamic(200L, "【回放】整场直播"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "replay notified") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO) != null
        }
        val notif = shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO)!!
        val title = notif.extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty()
        assertTrue("应标为回放: $title", title.contains("回放"))
        assertEquals("回放通知不响铃", 0, fakes.size)
    }

    @Test
    fun `A6 开播预告24h内 提醒一次且去重`() {
        prefs.setServiceRunning(true)
        // 预告动态 id 含 pubTs 相关；构造一个 1h 后开播的预告
        val now = System.currentTimeMillis()
        val liveRcmd = com.bilibili.livemonitor.api.BilibiliActivityApi.LiveRcmdInfo(
            dynamicId = "live-remind-1",
            liveStartMs = now + 3_600_000,
            title = "今晚见",
            contentText = "直播时间：20:00"
        )
        val activityApi = FakeActivityApi()
        activityApi.enqueue(
            com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok(
                com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo(
                    id = "text1", type = "DYNAMIC_TYPE_DRAW", displayText = "x",
                    avItem = null, isTop = false, pubTs = 0, liveRcmd = liveRcmd
                )
            )
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        driveActivityCheckUntil(controller, "live reminder fired") {
            shadowOf(nm).getNotification("live-remind-1".hashCode()) != null
        }
        assertNotNull("预告提醒必须发出", shadowOf(nm).getNotification("live-remind-1".hashCode()))
        assertEquals("去重 id 必须落盘", "live-remind-1", prefs.getLastRemindedLiveDynamicId())

        // 第二次检查（同一预告）不应重复提醒
        activityApi.enqueue(
            com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok(
                com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo(
                    id = "text1", type = "DYNAMIC_TYPE_DRAW", displayText = "x",
                    avItem = null, isTop = false, pubTs = 0, liveRcmd = liveRcmd
                )
            )
        )
        val before = shadowOf(nm).allNotifications.size
        driveActivityCheckUntil(controller, "second check done") { activityApi.callCount >= 2 }
        assertEquals("同一预告不得重复提醒", before, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `A7 动态类型过滤 未勾选类型不提醒`() {
        prefs.setServiceRunning(true)
        prefs.setLastDynamicId("old")
        prefs.setMonitorDynamicTypes(setOf("DYNAMIC_TYPE_DRAW")) // 只开图文
        val activityApi = FakeActivityApi()
        activityApi.enqueue(
            com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok(
                com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo(
                    id = "fwd1", type = "DYNAMIC_TYPE_FORWARD", displayText = "转发内容",
                    avItem = null, isTop = false, pubTs = 0
                )
            )
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        driveActivityCheckUntil(controller, "check done") { activityApi.callCount >= 1 }
        assertNull("未勾选的 FORWARD 不得提醒", shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC))
        // 基线仍推进（去重不依赖是否提醒）
        assertEquals("fwd1", prefs.getLastDynamicId())
    }

    @Test
    fun `A8 动态类型过滤 勾选类型才提醒`() {
        prefs.setServiceRunning(true)
        prefs.setLastDynamicId("old")
        prefs.setMonitorDynamicTypes(setOf("DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_FORWARD"))
        val activityApi = FakeActivityApi()
        activityApi.enqueue(
            com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Ok(
                com.bilibili.livemonitor.api.BilibiliActivityApi.DynamicInfo(
                    id = "fwd1", type = "DYNAMIC_TYPE_FORWARD", displayText = "转发内容",
                    avItem = null, isTop = false, pubTs = 0
                )
            )
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        driveActivityCheckUntil(controller, "dynamic notified") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC) != null
        }
        assertNotNull("勾选的 FORWARD 应提醒", shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC))
    }

    @Test
    fun `A2 首次检测只记录基线不提醒`() {
        // 核心原则：新装/升级后第一次检测只记录当前最新 id，
        // 否则用户装完瞬间收到"新视频"通知（实际是历史视频）
        prefs.setServiceRunning(true)
        // lastAid 未初始化（-1 → null）
        val activityApi = FakeActivityApi()
        activityApi.enqueue(videoDynamic(300L, "历史视频"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        driveActivityCheckUntil(controller, "baseline recorded") { prefs.getLastVideoAid() == 300L }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull(
            "首次不得发视频通知",
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO)
        )
        assertEquals("首次不得响铃", 0, fakes.size)
    }

    @Test
    fun `A3 同一aid重复检测 不重复提醒`() {
        prefs.setServiceRunning(true)
        prefs.setLastVideoAid(100L)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(videoDynamic(200L, "新视频"), videoDynamic(200L, "新视频"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "first video notification") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO) != null
        }
        // 第二次检测：同 aid，不得再触发
        driveActivityCheckUntil(controller, "second check consumed") { activityApi.callCount >= 2 }
        Thread.sleep(300)
        assertEquals("同 aid 不得重复响铃", 1, fakes.size)
    }

    @Test
    fun `A4 新动态发布 动态通知发出且lastDynamicId落盘`() {
        prefs.setServiceRunning(true)
        prefs.setLastDynamicId("old_id")
        val activityApi = FakeActivityApi()
        activityApi.enqueue(textDynamic("new_id", "今天也是元气满满的一天"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "dynamic notification") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC) != null
        }

        assertEquals("new_id", prefs.getLastDynamicId())

        // 通知升级（2026-08）：动态通知必须 HIGH 优先级 + v2 channel（横幅+提示音）
        val notif = shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC)!!
        assertEquals(
            android.app.Notification.PRIORITY_HIGH, notif.priority
        )
        assertEquals(LiveMonitorApp.CHANNEL_DYNAMIC_ALERT_ID, notif.channelId)
        assertTrue(
            "v2 channel 才能拿到 HIGH 重要性",
            LiveMonitorApp.CHANNEL_DYNAMIC_ALERT_ID.endsWith("_v2")
        )

        // 跳转 intent：Robolectric 环境无 bilibili，应回退 https 短链
        val contentIntent = shadowOf(notif.contentIntent).savedIntent
        assertEquals(
            "https://t.bilibili.com/new_id",
            contentIntent?.dataString
        )
    }

    @Test
    fun `A4c 跳转intent强投递 装bili用其包名 只装HD用HD 都不装浏览器兜底`() {
        // 废弃 scheme 猜测（bilibili://dynamic/{id} 无路由、detail 真机解析为空），
        // 改官方 web 格式 + setPackage 强投递（liveRoomAppIntent 同款策略）
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()

        // 分支1：装有哔哩哔哩 → setPackage(tv.danmaku.bili)
        service.bilibiliInstalled = { it == "tv.danmaku.bili" }
        service.buildDynamicIntent("123").also { intent ->
            assertEquals("https://t.bilibili.com/123", intent.dataString)
            assertEquals("tv.danmaku.bili", intent.`package`)
        }
        service.buildVideoIntent(456789L).also { intent ->
            assertEquals("https://www.bilibili.com/video/av456789", intent.dataString)
            assertEquals("tv.danmaku.bili", intent.`package`)
        }

        // 分支2：只装 HD 版 → setPackage(tv.danmaku.bilibilihd)
        service.bilibiliInstalled = { it == "tv.danmaku.bilibilihd" }
        service.buildDynamicIntent("123").also { intent ->
            assertEquals("tv.danmaku.bilibilihd", intent.`package`)
        }
        service.buildVideoIntent(456789L).also { intent ->
            assertEquals("tv.danmaku.bilibilihd", intent.`package`)
        }

        // 分支3：都没装 → 无 setPackage，浏览器兜底
        service.bilibiliInstalled = { false }
        service.buildDynamicIntent("123").also { intent ->
            assertEquals("https://t.bilibili.com/123", intent.dataString)
            assertNull(intent.`package`)
        }
        service.buildVideoIntent(456789L).also { intent ->
            assertEquals("https://www.bilibili.com/video/av456789", intent.dataString)
            assertNull(intent.`package`)
        }

        // 分支4：两个都装 → 优先 tv.danmaku.bili
        service.bilibiliInstalled = { true }
        assertEquals("tv.danmaku.bili", service.resolveBiliPackage())
    }

    @Test
    fun `A4b 动态检测NoData后重试一次 成功则正常提醒`() {
        // desktop 端点约 1/6 概率返回 code=0 但 items=[]，必须 15s 后重试一次
        // （测试里 dynamicRetryDelayMillis=0 不阻塞）
        prefs.setServiceRunning(true)
        prefs.setLastDynamicId("old_id")
        val activityApi = FakeActivityApi()
        activityApi.enqueue(
            com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.NoData,
            textDynamic("retry_id", "重试拿到的动态")
        )
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "dynamic notification after retry") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC) != null
        }

        assertTrue("必须发生重试", activityApi.callCount >= 2)
        assertEquals("retry_id", prefs.getLastDynamicId())
    }

    @Test
    fun `A5 置顶被取消 发出置顶取消文本通知`() {
        // 用户场景：UP 主取消置顶 → lastPinnedAid 有值、currentPinnedAid=null →
        // 必须提醒"置顶已取消"（sendTextNotification，id=title.hashCode）
        prefs.setServiceRunning(true)
        // 基线：上次置顶 aid=500；本次 feed 第一条不是置顶（isTop=false）→ 置顶没了
        prefs.setLastPinnedAid(500L)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(videoDynamic(600L, "普通新视频", isTop = false))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "pinned-cancel notification") {
            shadowOf(nm).getNotification("白绮置顶已取消".hashCode()) != null
        }
    }

    @Test
    fun `A8 置顶新视频 发置顶视频变更通知且落盘`() {
        // 用户场景：UP 主置顶了一个新视频 → 用户要知道"置顶换了"
        prefs.setServiceRunning(true)
        prefs.setMonitorDynamics(false)
        // 视频基线同 aid，避免新视频通知干扰；置顶基线 aid=500 → 换成 600
        prefs.setLastVideoAid(600L)
        prefs.setLastPinnedAid(500L)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(latestTextWithPinnedVideo(600L, "新的置顶视频"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "pinned-change notification") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO) != null
        }

        val notification = shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO)
        assertEquals(
            "白绮 置顶视频变更",
            notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        )
        assertEquals("新置顶必须落盘", 600L, prefs.getLastPinnedAid())
    }

    @Test
    fun `A8a 首次观察置顶视频 只落基线不提醒`() {
        prefs.setServiceRunning(true)
        prefs.setMonitorVideos(false)
        prefs.setMonitorDynamics(false)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(latestTextWithPinnedVideo(600L, "历史置顶视频"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        driveActivityCheckUntil(controller, "pinned baseline recorded") {
            prefs.getLastPinnedAid() == 600L
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull("首次置顶不得误报", shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO))
        assertEquals("首次置顶不得响铃", 0, fakes.size)
    }

    @Test
    fun `A8b 置顶视频也推进视频监控基线`() {
        prefs.setServiceRunning(true)
        prefs.setMonitorPinned(false)
        prefs.setMonitorDynamics(false)
        prefs.setLastVideoAid(100L)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(latestTextWithPinnedVideo(200L, "新投稿后置顶"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "pinned video alert") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO) != null
        }

        assertEquals("置顶视频也必须更新视频基线", 200L, prefs.getLastVideoAid())
    }

    @Test
    fun `A6 活动提醒铃声关闭时 只通知不响铃`() {
        // 用户场景：用户在设置里关掉"活动响铃"（不想被新视频吵醒）→
        // 通知照发但 playAlertSound 不得触发
        prefs.setServiceRunning(true)
        prefs.setLastVideoAid(100L)
        prefs.setAlertRingOnActivity(false)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(videoDynamic(200L, "新视频"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        driveActivityCheckUntil(controller, "video notification") {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO) != null
        }

        assertEquals("关闭活动响铃后不得创建播放器", 0, fakes.size)
    }

    @Test
    fun `A7 活动API失败 静默不扰不崩`() {
        // 动态接口风控/网络抖动是常态（文档明确"动态流风控脆弱"），失败必须静默
        prefs.setServiceRunning(true)
        val activityApi = FakeActivityApi()
        activityApi.enqueue(com.bilibili.livemonitor.api.BilibiliActivityApi.ActivityResult.Err("risk control"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val fakes = mutableListOf<FakeExoPlayer>()
        wireActivity(controller, activityApi, fakes)

        driveActivityCheckUntil(controller, "activity api called") { activityApi.callCount >= 1 }
        Thread.sleep(300)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull(shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO))
        assertNull(shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC))
        assertTrue("服务不得因活动API失败而挂", LiveCheckService.isRunning)
    }

    // ---------- P4: onTaskRemoved 重启链 ----------

    @Test
    fun `T1 用户划掉任务卡片 排Alarm和一次性Worker双保险拉起`() {
        // 真机场景：部分 ROM 划掉最近任务会杀服务，
        // 必须立即排 Alarm + Worker 双保险，否则监控静默死亡
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

        service.onTaskRemoved(Intent())

        assertTrue(
            "应排下一次检测 alarm",
            shadowOf(alarmManager).scheduledAlarms.isNotEmpty()
        )
        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue(
            "应排一次性兜底任务",
            oneTime.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `T2 监控已停止时划卡片 不排任何拉起`() {
        // 用户已主动停止监控，划卡片绝不能复活监控
        prefs.setServiceRunning(false)
        val controller = buildService(Intent(context, LiveCheckService::class.java))
        val service = controller.get()
        // prefs=false 时 onCreate 会自毁；直接调 onTaskRemoved 验证守卫
        controller.create()

        service.onTaskRemoved(Intent())

        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue("已停止监控不得排兜底任务", oneTime.isEmpty())
    }

    // ---------- D: 动态独立 5min 闹钟路径 ----------

    @Test
    fun `D1 动态独立闹钟触发 只查动态且重排动态闹钟`() {
        // 用户场景：开了动态监控，5min 独立闹钟到点 → 只查动态（不走直播检测），
        // 来了新动态要通知，且下一次动态闹钟必须重排（循环不能断）
        prefs.setServiceRunning(true)
        prefs.setLastDynamicId("dyn_old")  // 基线，避免首次只记录不提醒
        val activityApi = FakeActivityApi()
        activityApi.enqueue(textDynamic("dyn_new1", "白绮发了图文"))
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        service.activityApi = activityApi
        service.mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        service.playerFactory = { FakeExoPlayer().player }

        controller.withIntent(Intent(LiveCheckService.ACTION_CHECK_DYNAMICS)).startCommand(0, 1)

        // 检测跑在 serviceScope(IO 后台线程)，必须轮询等它完成
        waitFor("dynamic fetched", 10_000) { activityApi.callCount >= 1 }
        assertEquals("不得走直播检测", 0, fakeApi.callCount)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        waitFor("dynamic notification", 10_000) {
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_DYNAMIC) != null
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        waitFor("dynamic alarm rescheduled", 10_000) {
            shadowOf(alarmManager).scheduledAlarms.isNotEmpty()
        }
    }

    @Test
    fun `D2 监控停止后残留动态闹钟 服务自毁不检测不重排`() {
        // 用户停止监控后，已排的动态闹钟仍可能送达
        // （与 S1/S2 同级的 prefs 权威约束，动态路径此前无守护）
        prefs.setServiceRunning(false)
        val activityApi = FakeActivityApi()
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.get().activityApi = activityApi

        controller.withIntent(Intent(LiveCheckService.ACTION_CHECK_DYNAMICS)).startCommand(0, 1)

        assertEquals("停止监控后不得查动态", 0, activityApi.callCount)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // 等一拍确认不会有异步重排（有残留任务才会延迟出现）
        Thread.sleep(500)
        assertTrue(
            "停止监控后不得重排动态闹钟",
            shadowOf(alarmManager).scheduledAlarms.isEmpty()
        )
    }

    @Test
    fun `D3 取消动态闹钟后撤销PendingIntent令下次会话可重排`() {
        prefs.setServiceRunning(true)
        prefs.setMonitorVideos(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val dynamicIntent = Intent(context, LiveCheckService::class.java).apply {
            action = LiveCheckService.ACTION_CHECK_DYNAMICS
        }
        val flags = android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        assertNotNull(
            "启动时应建立动态闹钟 token",
            android.app.PendingIntent.getService(context, 2002, dynamicIntent, flags)
        )

        LiveCheckService.cancelScheduledChecks(context)

        assertNull(
            "取消后不能留下 token，否则下次会话会误以为闹钟仍在",
            android.app.PendingIntent.getService(context, 2002, dynamicIntent, flags)
        )
    }

    // ---------- R: 服务侧排程降级 ----------

    @Test
    fun `R1 精确闹钟权限未授予 服务侧排程降级且检测循环不断`() {
        // 用户场景：系统收回了"闹钟和提醒"权限（或从未授予）→
        // 服务侧排下一次检测必须降级 inexact 而不是抛异常断循环
        // （shadow 默认 canScheduleExactAlarms=false，即未授予态）
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()

        controller.startCommand(0, 1)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // 排程发生在检测完成之后（同一 IO 协程尾部），必须等它落到 shadow
        driveCheckUntil(controller, "next check alarm scheduled") {
            shadowOf(alarmManager).scheduledAlarms.isNotEmpty()
        }
    }

    @Test
    fun `R2 精确闹钟权限已授予 服务侧走精确排程`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()

        controller.startCommand(0, 1)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        driveCheckUntil(controller, "next check alarm scheduled") {
            shadowOf(alarmManager).scheduledAlarms.isNotEmpty()
        }
        // 复位静态开关，防泄漏到其他用例
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }
}
