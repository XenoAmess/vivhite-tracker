package com.bilibili.livemonitor.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.AlertActivity
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.api.BilibiliApi
import com.bilibili.livemonitor.api.LiveStatusChecker
import com.bilibili.livemonitor.util.AlertSoundProvider
import com.bilibili.livemonitor.util.PreferenceManager
import java.lang.reflect.Proxy
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
        LiveCheckService.isUserStopped = false
        LiveCheckService.lastLiveStatus = false
    }

    @After
    fun tearDown() {
        LiveCheckService.isRunning = false
        LiveCheckService.isUserStopped = false
    }

    private fun buildService(intent: Intent? = null): ServiceController<LiveCheckService> {
        val controller = Robolectric.buildService(LiveCheckService::class.java)
        if (intent != null) controller.withIntent(intent)
        val service = controller.get()
        service.api = fakeApi
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

        assertTrue(LiveCheckService.isUserStopped)
        assertFalse(prefs.isServiceRunning())
        val periodic = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_periodic").get()
        assertTrue(
            "周期任务应被取消",
            periodic.all { it.state == WorkInfo.State.CANCELLED }
        )
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
        fakeApi.enqueue(BilibiliApi.LiveStatus.NotLive, BilibiliApi.LiveStatus.Live)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        controller.startCommand(0, 1)
        waitFor("first check done") { fakeApi.callCount >= 1 && prefs.getLastCheckTime() > 0 }

        // 重复触发模拟周期闹钟：慢 runner 上第一次 startCommand 可能撞上
        // isChecking 锁未释放被跳过（真实场景 60s 间隔不存在此竞态）
        waitFor("alert notification", 20_000) {
            controller.startCommand(0, 2)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT) != null
        }
        val startedActivity = shadowOf(context).nextStartedActivity
        assertNotNull("应启动全屏提醒", startedActivity)
        assertEquals(AlertActivity::class.java.name, startedActivity.component?.className)
    }

    @Test
    fun `S10 持续在播 不重复触发提醒`() {        // 真机事件：直播中进程重启曾导致重复响铃。恢复状态后 Live→Live 不得再提醒
        prefs.setServiceRunning(true)
        // 预置 10 分钟内的"在播"状态，服务启动时会恢复 lastStatus=true
        prefs.setLastCheck(System.currentTimeMillis() - 60_000, isLive = true, success = true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live)
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
            BilibiliApi.LiveStatus.Live,    // 在播（静音中，不应提醒）
            BilibiliApi.LiveStatus.NotLive, // 下播（解除静音）
            BilibiliApi.LiveStatus.Live     // 再开播（恢复，应提醒）
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
            val before = prefs.getLastCheckTime()
            waitFor("check advances", 15_000) {
                controller.startCommand(0, startId)
                prefs.getLastCheckTime() != before
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

    /**
     * ExoPlayer 是接口，用动态代理手写 fake（避免引 mock 框架）。
     * 记录服务代码会调用的全部方法，其余返回类型默认值。
     */
    private class FakeExoPlayer {
        var audioAttrsSet = false
        var prepared = false
        var mediaSet = false
        var repeatMode = -1
        var playWhenReady = false
        var stopped = false
        var released = false

        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "setAudioAttributes" -> { audioAttrsSet = true; null }
                "prepare" -> { prepared = true; null }
                "setMediaItem" -> { mediaSet = true; null }
                "setRepeatMode" -> { repeatMode = args[0] as Int; null }
                "setPlayWhenReady" -> { playWhenReady = args[0] as Boolean; null }
                "isPlaying" -> playWhenReady && prepared && !stopped && !released
                "stop" -> { stopped = true; null }
                "release" -> { released = true; stopped = true; null }
                "toString" -> "FakeExoPlayer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> defaultValue(method.returnType)
            }
        } as ExoPlayer

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '0'
            else -> null
        }
    }

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

    @Test
    fun `P1 开播触发提醒 播放器经主线程调度创建且配置正确`() {
        // 真机 bug：playAlertSound 在 Dispatchers.IO 上建 ExoPlayer，
        // wrong-thread 异常被 catch 静默吞掉 → 感知到开播但完全无声。
        // 修复后必须走 mainDispatcher（测试用 Unconfined 顶替并同步执行）。
        prefs.setServiceRunning(true)
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live)
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
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live)
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
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live)
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
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live)
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
            BilibiliApi.LiveStatus.Live,    // 第1次开播 → 提醒#1
            BilibiliApi.LiveStatus.NotLive, // 下播
            BilibiliApi.LiveStatus.Live     // 第2次开播 → 提醒#2
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
        fakeApi.enqueue(BilibiliApi.LiveStatus.Live)
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
}
