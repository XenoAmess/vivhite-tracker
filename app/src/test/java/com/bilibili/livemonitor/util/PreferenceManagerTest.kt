package com.bilibili.livemonitor.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
