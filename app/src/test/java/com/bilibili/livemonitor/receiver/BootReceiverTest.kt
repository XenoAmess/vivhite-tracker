package com.bilibili.livemonitor.receiver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * BootReceiver（B4）。
 * 真机场景（回归实测）：模拟器重启/快照恢复触发 BOOT_COMPLETED，
 * 监控标记为 true 时服务自动恢复，为 false 时保持安静。
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `开机且监控标记为true 自动拉起服务`() {
        PreferenceManager(context).setServiceRunning(true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val startedIntent = shadowOf(context.applicationContext as android.app.Application)
            .peekNextStartedService()
        assertEquals(
            LiveCheckService::class.java.name,
            startedIntent?.component?.className
        )
    }

    @Test
    fun `开机但监控标记为false 不拉起`() {
        PreferenceManager(context).setServiceRunning(false)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(
            null,
            shadowOf(context.applicationContext as android.app.Application).peekNextStartedService()
        )
    }

    @Test
    fun `非开机广播 不动作`() {
        PreferenceManager(context).setServiceRunning(true)

        BootReceiver().onReceive(context, Intent("com.example.OTHER_ACTION"))

        assertEquals(
            null,
            shadowOf(context.applicationContext as android.app.Application).peekNextStartedService()
        )
    }

    @Test
    fun `开机拉起被FGS拒绝 降级为一次性WorkManager任务`() {
        // 真机场景：Android 12+ 开机时App处于后台受限状态，
        // startForegroundService 抛 FGS 异常，必须降级 WorkManager 兜底，
        // 否则重启手机后监控彻底死掉（用户完全无感知）
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            context, androidx.work.Configuration.Builder().build()
        )
        PreferenceManager(context).setServiceRunning(true)
        val receiver = BootReceiver()
        receiver.starter = object : ServiceStarter {
            override fun startForegroundService(context: android.content.Context, intent: Intent) {
                throw android.app.ForegroundServiceStartNotAllowedException("simulated boot FGS denial")
            }
        }

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val oneTime = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_one_time").get()
        org.junit.Assert.assertTrue(
            "开机 FGS 被拒必须降级排一次性任务",
            oneTime.any { it.state == androidx.work.WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `开机拉起 intent 带正确房间号`() {
        // 房间号是硬编码功能的唯一配置位，拉错房间等于监控报废
        PreferenceManager(context).setServiceRunning(true)
        var captured: Intent? = null
        val receiver = BootReceiver()
        receiver.starter = object : ServiceStarter {
            override fun startForegroundService(context: android.content.Context, intent: Intent) {
                captured = intent
            }
        }

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        org.junit.Assert.assertNotNull(captured)
        org.junit.Assert.assertEquals(
            LiveCheckService::class.java.name,
            captured?.component?.className
        )
        org.junit.Assert.assertEquals(
            PreferenceManager(context).getRoomId(),
            captured?.getLongExtra(LiveCheckService.EXTRA_ROOM_ID, -1L)
        )
    }
}
