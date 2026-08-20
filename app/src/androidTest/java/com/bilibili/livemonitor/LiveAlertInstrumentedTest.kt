package com.bilibili.livemonitor

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.api.BilibiliApi
import com.bilibili.livemonitor.api.LiveStatusChecker
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 开播提醒完整链路的真机/模拟器端到端验证（单测与手工场景之间的最后一环）。
 *
 * 覆盖的真实链路：
 * fake api(Live) → checkLiveStatus → handleResult → triggerAlert →
 * playAlertSound 在主线程创建【真实 ExoPlayer】→ 解码内置铃声海愿 → isPlaying。
 *
 * 单测只能证明"走了 mainDispatcher"（Robolectric 建不了 ExoPlayer），
 * AlertSoundInstrumentedTest 只证明"手工复刻的代码能播"，
 * 本测试证明"真实服务的 triggerAlert 路径在真机上确实响"。
 *
 * 这正是 2026-07-30 真机 bug（感知开播但不响铃）的回归守门员。
 */
@RunWith(AndroidJUnit4::class)
class LiveAlertInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var prefs: PreferenceManager

    /** 恒在播的 fake 检测源：驱动 handleResult(true) → triggerAlert */
    class AlwaysLiveApi : LiveStatusChecker {
        override suspend fun checkLiveStatus(roomId: Long): BilibiliApi.LiveStatus =
            BilibiliApi.LiveStatus.Live()
    }

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
        notificationManager().cancel(LiveMonitorApp.NOTIFICATION_ID_ALERT)
        shell("pm grant com.bilibili.livemonitor android.permission.POST_NOTIFICATIONS")
        shell("appops set com.bilibili.livemonitor SCHEDULE_EXACT_ALARM allow")
        shell("appops set com.bilibili.livemonitor SYSTEM_ALERT_WINDOW allow")
        // 先落 false：挡住上个会话残留 alarm/重启广播在清理窗口内复活服务
        prefs.setServiceRunning(false)
        // 清理用无副作用的 stopService（不排队 onStartCommand、不写 prefs）。
        // 此前用 ACTION_STOP_SERVICE 清理：intent 异步送达，其 prefs=false 写入
        // 会覆盖 setUp 随后写入的 true，导致测试的服务启动被守卫拦下（真踩过的坑）
        context.stopService(Intent(context, LiveCheckService::class.java))
        waitFor("service stopped", 15_000) { !LiveCheckService.isRunning }
        // 监控开启、活动监控全关（隔离真实活动 API 的网络噪音）、铃声用默认海愿
        prefs.setServiceRunning(true)
        prefs.setMonitorVideos(false)
        prefs.setMonitorPinned(false)
        prefs.setMonitorDynamics(false)
        // 铃声状态显式复位：设备上手工选过的铃声不能漏进用例（hermetic）
        prefs.setAlertSoundUri("")
        prefs.setAlertSoundTitle("")
        // 检测状态复位：上个用例落了 last_check_live=true 时，服务重启会恢复
        // lastStatus=true → Live→Live 不跳变 → 永不提醒（真踩过的坑）
        prefs.setLastCheck(0L, false, false)
        // 上个用例的播放器引用不得残留（isPlaying 轮询会命中陈旧实例）
        LiveCheckService.lastAlertPlayer = null
        LiveCheckService.apiOverride = AlwaysLiveApi()
    }

    @After
    fun tearDown() {
        notificationManager().cancel(LiveMonitorApp.NOTIFICATION_ID_ALERT)
        LiveCheckService.apiOverride = null
        prefs.setAlertSoundUri("")
        prefs.setAlertSoundTitle("")
        prefs.setServiceRunning(false)  // 先落 false，挡住 onDestroy 重启广播复活
        context.stopService(Intent(context, LiveCheckService::class.java))
        waitFor("service stopped", 15_000) { !LiveCheckService.isRunning }
        instrumentation.runOnMainSync {
            LiveCheckService.lastAlertPlayer?.release()
            LiveCheckService.lastAlertPlayer = null
        }
    }

    @Test
    fun 开播跳变后服务triggerAlert真实ExoPlayer起播且无静默失败() {
        triggerAlertAndWaitPlaying()

        // 提醒通知也真实发出
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        waitFor("alert notification posted", 10_000) {
            nm.activeNotifications.any { it.id == LiveMonitorApp.NOTIFICATION_ID_ALERT }
        }

        // 日志链路完整、无静默失败点
        val log = readMonitorLog()
        assertTrue("应有 triggerAlert 日志", log.contains("triggerAlert"))
        assertFalse(
            "不得出现 playAlertSound failed（wrong-thread 回归特征）",
            log.contains("playAlertSound failed")
        )
        assertFalse(
            "不得出现 all sound sources failed（铃声源加载回归）",
            log.contains("all sound sources failed")
        )
        assertFalse(
            "不得出现 alert playback error（异步解码失败回归）",
            log.contains("alert playback error")
        )
    }

    @Test
    fun 选定游园设施后开播提醒真实加载alert_6并起播() {
        // 回归（2026-07-31 用户反馈）：以为设了游园设施，开播实际播海愿。
        // 显式落 builtin:alert_6，验证提醒链真实加载该资源并起播、不回退默认
        prefs.setAlertSoundUri("builtin:alert_6")
        prefs.setAlertSoundTitle("遊園施設")
        val logStart = readMonitorLog().length

        triggerAlertAndWaitPlaying()

        // AppLogger 走单线程 executor 异步落盘（in-order），必须轮询等队列排空；
        // isPlaying 为真时 "alert playback started" 尚未入文件（真踩过的坑）
        var tail = ""
        waitFor("alert source log lines", 10_000) {
            val full = readMonitorLog()
            // 只断言本次触发新落的日志（设备上历史日志可能含其他用例的 fallback 记录）
            tail = if (full.length > logStart) full.substring(logStart) else full
            tail.contains("triggerAlert") &&
                tail.contains("builtin sound loaded: alert_6") &&
                tail.contains("alert playback started")
        }
        assertFalse("不得回退内置默认", tail.contains("primary source failed"))
        assertFalse("不得出现未知 key 兜底", tail.contains("unknown builtin key"))
        assertFalse("不得出现异步解码失败", tail.contains("alert playback error"))
    }

    /** MainActivity 前台化后起 FGS，等真实播放器创建且真实起播（isPlaying 须在播放器线程读） */
    private fun triggerAlertAndWaitPlaying() {
        instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        context.startForegroundService(Intent(context, LiveCheckService::class.java))

        waitFor("alert player created and playing", 30_000) {
            var playing = false
            instrumentation.runOnMainSync {
                playing = runCatching {
                    LiveCheckService.lastAlertPlayer?.isPlaying == true
                }.getOrDefault(false)
            }
            playing
        }
    }

    private fun readMonitorLog(): String {
        val f = java.io.File(context.filesDir, "logs/monitor.log")
        return if (f.exists()) f.readText() else ""
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.BufferedReader(
                java.io.InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd))
            ).readText()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout: $what")
            Thread.sleep(200)
        }
    }
}
