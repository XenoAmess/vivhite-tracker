package com.bilibili.livemonitor.receiver

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.domain.MagicPeriod
import com.bilibili.livemonitor.service.MagicAlertService
import com.bilibili.livemonitor.util.MagicPeriodStore
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * 魔法期结束提醒链（镜像 BootReceiverTest 模式）：
 * 触发 → 响铃（fake 播放器）+ 通知 + 自排下一个未来结束。
 */
@RunWith(RobolectricTestRunner::class)
class MagicEndReceiverTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager
    private var alertStarted = false

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
        prefs.setMagicPeriodsJson("[]")
        alertStarted = false
        MagicEndReceiver.alertStarter = { alertStarted = true }
    }

    @After
    fun tearDown() {
        MagicEndReceiver.alertStarter = MagicAlertService::start
    }

    @Test
    fun `有未来结束 触发后通知发出且重排`() {
        val future = System.currentTimeMillis() + 3600_000L
        MagicPeriodStore.save(prefs, listOf(MagicPeriod(future - 3 * 86_400_000L, future)))

        MagicEndReceiver().onReceive(context, Intent())

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(
            "魔法期结束通知必须发出",
            shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_MAGIC)
        )
        assertTrue("Receiver 必须交给前台服务播放铃声", alertStarted)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(
            "必须重排下一个未来结束闹钟",
            shadowOf(alarmManager).scheduledAlarms.isNotEmpty()
        )
    }

    @Test
    fun `无未来结束 触发后取消闹钟`() {
        val past = System.currentTimeMillis() - 3600_000L
        MagicPeriodStore.save(prefs, listOf(MagicPeriod(past - 3 * 86_400_000L, past)))

        MagicEndReceiver().onReceive(context, Intent())

        // 通知照发（这是本次到点的提醒）
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_MAGIC))
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(
            "无未来结束不得再排闹钟",
            shadowOf(alarmManager).scheduledAlarms.isEmpty()
        )
    }

    @Test
    fun `空记录 通知照发但不排闹钟`() {
        MagicEndReceiver().onReceive(context, Intent())
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_MAGIC))
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `前台服务启动被拒绝时 仍以高优先级系统通知兜底`() {
        MagicEndReceiver.alertStarter = { throw IllegalStateException("fgs not allowed") }

        MagicEndReceiver().onReceive(context, Intent())

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = shadowOf(nm).getNotification(LiveMonitorApp.NOTIFICATION_ID_MAGIC)
        assertNotNull("前台服务失败时也必须可见提醒", notification)
        assertEquals(android.app.Notification.PRIORITY_MAX, notification!!.priority)
    }
}
