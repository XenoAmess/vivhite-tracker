package com.bilibili.livemonitor.worker

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Worker 三分支（B1/B2/B3），全部来自真机验证过的行为：
 * - 用户手动停止后，Worker 不得再拉起服务
 * - 服务活着时 Worker 只确认不重启（真机日志 "worker triggered, service isRunning=true"）
 * - 服务死了且应该运行时，Worker 负责拉起（双轨兜底）
 */
@RunWith(RobolectricTestRunner::class)
class LiveCheckWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        LiveCheckService.isRunning = false
    }

    private fun buildWorker(): LiveCheckWorker {
        return TestListenableWorkerBuilder<LiveCheckWorker>(context).build()
    }

    @Test
    fun `用户已停止监控 Worker跳过不拉起`() = runBlocking {
        // B1 真机场景：用户点了"停止监控"，Worker 周期触发时绝不能把服务再拉起来
        PreferenceManager(context).setServiceRunning(false)
        LiveCheckService.isRunning = false

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNoServiceStarted()
    }

    @Test
    fun `服务活着 Worker跳过不重启`() = runBlocking {
        // B2 真机场景：60s Alarm 链路正常时，15min Worker 触发只确认不动作
        PreferenceManager(context).setServiceRunning(true)
        LiveCheckService.isRunning = true

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNoServiceStarted()
    }

    @Test
    fun `服务死了且应该运行 Worker拉起服务`() = runBlocking {
        // B3 真机场景：服务被系统杀掉（内存压力/Doze），Worker 是兜底拉起路径
        PreferenceManager(context).setServiceRunning(true)
        LiveCheckService.isRunning = false

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val startedIntent = shadowOf(context.applicationContext as android.app.Application)
            .peekNextStartedService()
        assertEquals(
            LiveCheckService::class.java.name,
            startedIntent?.component?.className
        )
    }

    private fun assertNoServiceStarted() {
        val startedIntent = shadowOf(context.applicationContext as android.app.Application)
            .peekNextStartedService()
        assertEquals(null, startedIntent)
    }
}
