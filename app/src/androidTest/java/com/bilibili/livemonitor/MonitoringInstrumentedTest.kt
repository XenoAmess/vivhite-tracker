package com.bilibili.livemonitor

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * 真机核心场景的自动化版本（在模拟器/真机上运行）。
 * 对应 2026-07-23/24 手工真机测试的三条主链路：
 * 启动监控→首轮检测、熄屏 Doze 下周期检测、停止清理。
 *
 * 注意：检测依赖真实 B 站 API，需要网络；不断言"开播提醒"
 * （主播状态不可控），断言的是监控基础设施行为。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MonitoringInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
        // 运行权限前置：与真实用户引导路径等价，全部通过 shell 授予
        shell("pm grant com.bilibili.livemonitor android.permission.POST_NOTIFICATIONS")
        shell("appops set com.bilibili.livemonitor SCHEDULE_EXACT_ALARM allow")
        shell("dumpsys deviceidle whitelist +com.bilibili.livemonitor")
        // 确保干净起点
        stopMonitoringIfRunning()
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        stopMonitoringIfRunning()
        shell("dumpsys deviceidle unforce")
    }

    @Test
    fun test1_启动监控后服务前台运行且首轮检测落盘() {
        // 真机场景：用户点"开始监控" → FGS 启动 → 60 秒内完成首次真实 API 检测
        startMonitoring()

        waitFor("service running", 15_000) { LiveCheckService.isRunning }
        waitFor("first check persisted", 60_000) { prefs.getLastCheckTime() > 0 }
    }

    @Test
    fun test2_强制Doze下周期检测仍然推进() {
        // 真机核心场景：熄屏+不充电+后台（本项目最初的 bug）。
        // 与手工验证过的步骤一致：先熄屏，再强制 deep idle，
        // 依靠精确闹钟+白名单，60s 检测必须继续。
        startMonitoring()
        waitFor("first check", 60_000) { prefs.getLastCheckTime() > 0 }

        shell("input keyevent KEYCODE_SLEEP")
        shell("dumpsys deviceidle force-idle")
        try {
            val before = prefs.getLastCheckTime()
            // 窗口放宽到 150s：Doze 下网络慢时单次检测可能 25s 超时 + 15s 后重试，
            // 最坏 ~100s 才落盘（手工真机实测窗口为 150s）
            waitFor("check advances in doze", 150_000) {
                prefs.getLastCheckTime() != before
            }
        } catch (e: Throwable) {
            throw AssertionError("${e.message}\n${readMonitorLogTail()}", e)
        } finally {
            shell("dumpsys deviceidle unforce")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
    }

    private fun readMonitorLogTail(): String {
        return try {
            val logFile = java.io.File(context.filesDir, "logs/monitor.log")
            if (logFile.exists()) {
                "=== monitor.log tail ===\n" + logFile.readLines().takeLast(25).joinToString("\n")
            } else {
                "=== monitor.log does not exist ==="
            }
        } catch (e: Exception) {
            "readMonitorLogTail failed: ${e.message}"
        }
    }

    @Test
    fun test3_停止监控后服务销毁且不再检测() {
        // 真机场景：用户点"停止监控" → 服务/闹钟/Worker 全部清理，不再有任何检测
        startMonitoring()
        waitFor("first check", 60_000) { prefs.getLastCheckTime() > 0 }

        try {
            stopMonitoringIfRunning()
            waitFor("service stopped", 15_000) { !LiveCheckService.isRunning }

            val lastCheck = prefs.getLastCheckTime()
            Thread.sleep(70_000) // 跨越一个 60s 周期
            org.junit.Assert.assertEquals(
                "停止后不应再有新检测", lastCheck, prefs.getLastCheckTime()
            )
        } catch (e: Throwable) {
            throw AssertionError("${e.message}\n${readMonitorLogTail()}", e)
        }
    }

    private fun startMonitoring() {
        prefs.setServiceRunning(true)
        val intent = Intent(context, LiveCheckService::class.java).apply {
            putExtra(LiveCheckService.EXTRA_ROOM_ID, 11258892L)
        }
        context.startForegroundService(intent)
    }

    private fun stopMonitoringIfRunning() {
        prefs.setServiceRunning(false)
        // 只在服务真的活着时才发 STOP：否则 startService 会为投递 intent
        // 创建服务，STOP 异步到达会竞杀后续 startMonitoring 启动的实例
        // （实测日志：晚到的 STOP 把新服务 onDestroy 并把 prefs 刷成 false）
        if (!LiveCheckService.isRunning) return
        val intent = Intent(context, LiveCheckService::class.java).apply {
            action = LiveCheckService.ACTION_STOP_SERVICE
        }
        context.startService(intent)
        runCatching {
            waitFor("service stopped", 10_000) { !LiveCheckService.isRunning }
        }
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String) {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun waitFor(what: String, timeoutMillis: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout waiting for: $what")
            }
            Thread.sleep(500)
        }
        assertTrue(what, true)
    }
}
