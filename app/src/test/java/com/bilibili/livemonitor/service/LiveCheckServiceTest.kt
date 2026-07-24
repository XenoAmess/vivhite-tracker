package com.bilibili.livemonitor.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.AlertActivity
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.api.BilibiliApi
import com.bilibili.livemonitor.api.LiveStatusChecker
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `S11 提醒响铃中停止监控 铃声立即停止并释放`() {
        // 用户需求：响铃时点停止监控/打开直播间，铃声必须停。
        // 同时覆盖旧 bug：MediaPlayer 原为局部变量，服务 10 秒内被停则协程
        // 取消、铃声永循环直到进程死亡。
        // 注：Robolectric 沙箱无法真正播放铃声，直接挂载 alertPlayer 模拟响铃中
        prefs.setServiceRunning(true)
        val controller = buildService(Intent(context, LiveCheckService::class.java)).create()
        val service = controller.get()
        service.alertPlayer = android.media.MediaPlayer()
        assertTrue(service.alertPlayer != null)

        controller.withIntent(Intent(LiveCheckService.ACTION_STOP_SERVICE)).startCommand(0, 1)

        assertNull("停止监控后铃声播放器必须释放", service.alertPlayer)
    }
}
