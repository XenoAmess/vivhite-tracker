package com.bilibili.livemonitor.receiver

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AlarmReceiver FGS 降级路径（P2）。
 * 真机场景：Android 12+ 后台启动 FGS 被拒，必须降级到 WorkManager 一次性任务，
 * 而不是像旧版一样静默吞掉异常导致监控彻底停摆。
 */
@RunWith(RobolectricTestRunner::class)
class AlarmReceiverTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    class ThrowingStarter : ServiceStarter {
        override fun startForegroundService(context: Context, intent: Intent) {
            throw IllegalStateException("simulated FGS start denial")
        }
    }

    /** 抛 Android 12+ 真实的 FGS 拒绝异常（覆盖 FGS 专属日志分支） */
    class FgsNotAllowedStarter : ServiceStarter {
        override fun startForegroundService(context: Context, intent: Intent) {
            throw android.app.ForegroundServiceStartNotAllowedException("simulated real FGS denial")
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().build()
        )
        prefs = PreferenceManager(context)
    }

    @Test
    fun `FGS启动被拒 降级为一次性WorkManager任务`() {
        // 真机事件：Doze/后台时 startForegroundService 抛异常，旧版静默吞掉后监控停摆
        prefs.setServiceRunning(true)
        val receiver = AlarmReceiver()
        receiver.starter = ThrowingStarter()

        receiver.onReceive(context, Intent())

        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue(
            "应降级排一次性任务",
            oneTime.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `prefs为false 直接返回不启动不排任务`() {
        // 用户停止后，残留的闹钟触发绝不能复活监控
        prefs.setServiceRunning(false)
        val receiver = AlarmReceiver()

        receiver.onReceive(context, Intent())

        assertNull(shadowOf(context).peekNextStartedService())
        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue(oneTime.isEmpty())
    }

    @Test
    fun `正常路径 启动前台服务`() {
        prefs.setServiceRunning(true)

        AlarmReceiver().onReceive(context, Intent())

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService::class.java.name, started?.component?.className)
    }

    @Test
    fun `真实FGS拒绝异常 同样降级WorkManager不崩`() {
        // Android 12+ 专属异常类型 ForegroundServiceStartNotAllowedException
        // 走的是与通用异常不同的日志分支，但降级行为必须一致
        prefs.setServiceRunning(true)
        val receiver = AlarmReceiver()
        receiver.starter = FgsNotAllowedStarter()

        receiver.onReceive(context, Intent())

        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue(
            "FGS 专属异常也必须降级排一次性任务",
            oneTime.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `精确闹钟权限已授予 走setExactAndAllowWhileIdle路径`() {
        // 默认 shadow 里 canScheduleExactAlarms=false（走 inexact 兜底分支），
        // 授予后必须走 setExactAndAllowWhileIdle，且 alarm 被排上
        prefs.setServiceRunning(true)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        AlarmReceiver().onReceive(context, Intent())

        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertTrue("应排上下一次 alarm", scheduled.isNotEmpty())
    }
}
