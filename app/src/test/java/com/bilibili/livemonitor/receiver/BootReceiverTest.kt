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
}
