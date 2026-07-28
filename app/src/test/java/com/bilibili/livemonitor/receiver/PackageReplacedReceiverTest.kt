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
 * PackageReplacedReceiver 升级恢复（P2）。
 * 真机场景：覆盖安装后系统杀进程，监控中的服务要被拉起；
 * 用户已停止的监控绝不能被升级复活；FGS 被拒时降级 WorkManager。
 */
@RunWith(RobolectricTestRunner::class)
class PackageReplacedReceiverTest {

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
    fun `升级后监控中 拉起服务`() {
        prefs.setServiceRunning(true)

        PackageReplacedReceiver().onReceive(
            context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        )

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService::class.java.name, started?.component?.className)
    }

    @Test
    fun `升级后用户已停止 不复活监控`() {
        // 关键不变量：prefs 是监控开关唯一权威，升级不能违背用户停止意图
        prefs.setServiceRunning(false)

        PackageReplacedReceiver().onReceive(
            context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        )

        assertNull(shadowOf(context).peekNextStartedService())
    }

    @Test
    fun `升级后FGS被拒 降级为一次性WorkManager任务`() {
        prefs.setServiceRunning(true)
        val receiver = PackageReplacedReceiver()
        receiver.starter = ThrowingStarter()

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val oneTime = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        assertTrue(
            "应降级排一次性任务",
            oneTime.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `非本包替换广播 不响应`() {
        prefs.setServiceRunning(true)

        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(context).peekNextStartedService())
    }
}
