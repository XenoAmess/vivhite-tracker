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
 * ServiceRestartReceiver FGS 降级路径（P2）。
 * 真机场景：服务被销毁后广播重启，FGS 启动被拒时降级 WorkManager。
 */
@RunWith(RobolectricTestRunner::class)
class ServiceRestartReceiverTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    class ThrowingStarter : ServiceStarter {
        override fun startForegroundService(context: Context, intent: Intent) {
            throw IllegalStateException("simulated FGS start denial")
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
    fun `重启时FGS被拒 降级为一次性WorkManager任务`() {
        prefs.setServiceRunning(true)
        val receiver = ServiceRestartReceiver()
        receiver.starter = ThrowingStarter()

        receiver.onReceive(context, Intent(LiveCheckService.ACTION_RESTART_SERVICE))

        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue(
            "应降级排一次性任务",
            oneTime.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `prefs为false 不重启`() {
        prefs.setServiceRunning(false)

        ServiceRestartReceiver().onReceive(context, Intent(LiveCheckService.ACTION_RESTART_SERVICE))

        assertNull(shadowOf(context).peekNextStartedService())
    }

    @Test
    fun `正常路径 重启服务`() {
        prefs.setServiceRunning(true)

        ServiceRestartReceiver().onReceive(context, Intent(LiveCheckService.ACTION_RESTART_SERVICE))

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService::class.java.name, started?.component?.className)
    }
}
