package com.bilibili.livemonitor.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 持久层 + 新鲜度边界（B5）。
 * 真机场景：进程被杀重启后，10 分钟内的状态要恢复，过期/失败的不能恢复。
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceManagerTest {

    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        prefs = PreferenceManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `roomId 存取 round trip`() {
        prefs.saveRoomId(12345L)
        assertEquals(12345L, prefs.getRoomId())
    }

    @Test
    fun `serviceRunning 存取 round trip`() {
        prefs.setServiceRunning(true)
        assertEquals(true, prefs.isServiceRunning())
        prefs.setServiceRunning(false)
        assertEquals(false, prefs.isServiceRunning())
    }

    @Test
    fun `默认 roomId 是白绮直播间`() {
        // 新装应用未设置时，默认监控 11258892
        assertEquals(11258892L, PreferenceManager(ApplicationProvider.getApplicationContext()).getRoomId())
    }

    @Test
    fun `10分钟内成功检测 状态可恢复`() {
        prefs.setLastCheck(System.currentTimeMillis() - 60_000L, isLive = true, success = true)
        assertEquals(true, prefs.getRecentLastStatus())
    }

    @Test
    fun `9分59秒边界内 状态可恢复`() {
        prefs.setLastCheck(System.currentTimeMillis() - 599_000L, isLive = false, success = true)
        assertEquals(false, prefs.getRecentLastStatus())
    }

    @Test
    fun `10分01秒过期 状态不可恢复`() {
        // 真机场景：进程死了超过 10 分钟，期间可能刚开播，必须视为首次
        prefs.setLastCheck(System.currentTimeMillis() - 601_000L, isLive = true, success = true)
        assertNull(prefs.getRecentLastStatus())
    }

    @Test
    fun `上次检测失败 状态不可恢复`() {
        // 真机场景：Doze 网络 Error 写入 success=false，重启后不能拿它当状态
        prefs.setLastCheck(System.currentTimeMillis() - 10_000L, isLive = false, success = false)
        assertNull(prefs.getRecentLastStatus())
    }

    @Test
    fun `从未检测 状态不可恢复`() {
        assertNull(prefs.getRecentLastStatus())
    }

    @Test
    fun `更新设置默认值`() {
        assertEquals(true, prefs.isAutoCheckUpdate())
        assertEquals(false, prefs.isAutoDownloadUpdate())
        assertEquals(0L, prefs.getLastUpdateCheckTime())
        assertEquals(-1, prefs.getDismissedVersionCode())
    }

    @Test
    fun `更新设置存取 round trip`() {
        prefs.setAutoCheckUpdate(false)
        assertEquals(false, prefs.isAutoCheckUpdate())
        prefs.setAutoDownloadUpdate(true)
        assertEquals(true, prefs.isAutoDownloadUpdate())
        prefs.setLastUpdateCheckTime(123456L)
        assertEquals(123456L, prefs.getLastUpdateCheckTime())
        prefs.setDismissedVersionCode(92)
        assertEquals(92, prefs.getDismissedVersionCode())
    }

    @Test
    fun `提醒铃声默认值是空串`() {
        // 新装应用未设置时，空串 = 使用内置默认铃声
        assertEquals("", prefs.getAlertSoundUri())
        assertEquals("", prefs.getAlertSoundTitle())
    }

    @Test
    fun `提醒铃声 uri 存取 round trip`() {
        prefs.setAlertSoundUri("builtin:alert_gentle")
        assertEquals("builtin:alert_gentle", prefs.getAlertSoundUri())
        prefs.setAlertSoundUri("system:content://settings/system/alarm_alert")
        assertEquals("system:content://settings/system/alarm_alert", prefs.getAlertSoundUri())
    }

    @Test
    fun `提醒铃声 title 存取 round trip`() {
        prefs.setAlertSoundTitle("柔和提示")
        assertEquals("柔和提示", prefs.getAlertSoundTitle())
        prefs.setAlertSoundTitle("我的录音.mp3")
        assertEquals("我的录音.mp3", prefs.getAlertSoundTitle())
    }

    @Test
    fun `提醒铃声 uri 可清空`() {
        // 用户点「恢复默认」时存空串
        prefs.setAlertSoundUri("builtin:alert_gentle")
        prefs.setAlertSoundUri("")
        assertEquals("", prefs.getAlertSoundUri())
    }

    // ========== 活动监控 11 键 ==========

    @Test
    fun `活动监控开关默认全关`() {
        assertEquals(false, prefs.isMonitorVideos())
        assertEquals(false, prefs.isMonitorPinned())
        assertEquals(false, prefs.isMonitorDynamics())
        assertEquals(false, prefs.isAlertRingOnActivity())
    }

    @Test
    fun `活动监控开关 round trip`() {
        prefs.setMonitorVideos(true)
        assertEquals(true, prefs.isMonitorVideos())
        prefs.setMonitorPinned(true)
        assertEquals(true, prefs.isMonitorPinned())
        prefs.setMonitorDynamics(true)
        assertEquals(true, prefs.isMonitorDynamics())
        prefs.setAlertRingOnActivity(true)
        assertEquals(true, prefs.isAlertRingOnActivity())
    }

    @Test
    fun `lastVideoAid 默认 -1`() {
        assertEquals(-1L, prefs.getLastVideoAid())
    }

    @Test
    fun `lastVideoAid round trip`() {
        prefs.setLastVideoAid(12345L)
        assertEquals(12345L, prefs.getLastVideoAid())
    }

    @Test
    fun `lastPinnedAid 默认 -1`() {
        assertEquals(-1L, prefs.getLastPinnedAid())
    }

    @Test
    fun `lastPinnedAid round trip`() {
        prefs.setLastPinnedAid(67890L)
        assertEquals(67890L, prefs.getLastPinnedAid())
    }

    @Test
    fun `lastDynamicId 默认空串`() {
        assertEquals("", prefs.getLastDynamicId())
    }

    @Test
    fun `lastDynamicId round trip`() {
        prefs.setLastDynamicId("dyn123456")
        assertEquals("dyn123456", prefs.getLastDynamicId())
    }
}
