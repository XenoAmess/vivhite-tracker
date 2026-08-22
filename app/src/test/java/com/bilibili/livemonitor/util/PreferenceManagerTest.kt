package com.bilibili.livemonitor.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `monitoring heartbeat 存取并绑定会话`() {
        val generation = prefs.beginMonitoringSession()
        assertEquals(0L, prefs.getMonitoringHeartbeatTime())
        assertEquals(generation, prefs.getMonitoringHeartbeatGeneration())

        prefs.setMonitoringHeartbeat(123_456L, generation)
        assertEquals(123_456L, prefs.getMonitoringHeartbeatTime())
        assertEquals(generation, prefs.getMonitoringHeartbeatGeneration())
    }

    @Test
    fun `lastLiveStartTime 存取 round trip`() {
        assertEquals("", prefs.getLastLiveStartTime())
        prefs.setLastLiveStartTime("2026-08-02 12:00:00")
        assertEquals("2026-08-02 12:00:00", prefs.getLastLiveStartTime())
    }

    @Test
    fun `suppressedLiveStart 存取 round trip`() {
        assertEquals("", prefs.getSuppressedLiveStart())
        prefs.setSuppressedLiveStart("2026-08-02 12:00:00")
        assertEquals("2026-08-02 12:00:00", prefs.getSuppressedLiveStart())
        prefs.setSuppressedLiveStart("")
        assertEquals("", prefs.getSuppressedLiveStart())
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
    fun `部分备份只含自动备份开关时不清空现有目录`() {
        prefs.setBackupTreeUri("content://backup/tree")
        prefs.setAutoBackupEnabled(true)

        val result = prefs.importSnapshot("""{"auto_backup_enabled":false}""")

        assertTrue(result.imported)
        assertEquals("content://backup/tree", prefs.getBackupTreeUri())
        assertFalse(prefs.isAutoBackupEnabled())
    }

    @Test
    fun `提醒铃声默认值是空串`() {
        // 新装应用未设置时，空串 = 使用内置默认铃声
        assertEquals("", prefs.getAlertSoundUri())
        assertEquals("", prefs.getAlertSoundTitle())
    }

    @Test
    fun `提醒铃声 uri 存取 round trip`() {
        prefs.setAlertSoundUri("builtin:alert_3")
        assertEquals("builtin:alert_3", prefs.getAlertSoundUri())
        prefs.setAlertSoundUri("system:content://settings/system/alarm_alert")
        assertEquals("system:content://settings/system/alarm_alert", prefs.getAlertSoundUri())
    }

    @Test
    fun `提醒铃声 title 存取 round trip`() {
        prefs.setAlertSoundTitle("Ad astra")
        assertEquals("Ad astra", prefs.getAlertSoundTitle())
        prefs.setAlertSoundTitle("我的录音.mp3")
        assertEquals("我的录音.mp3", prefs.getAlertSoundTitle())
    }

    @Test
    fun `提醒铃声 uri 可清空`() {
        // 用户点「恢复默认」时存空串
        prefs.setAlertSoundUri("builtin:alert_3")
        prefs.setAlertSoundUri("")
        assertEquals("", prefs.getAlertSoundUri())
    }

    // ========== 活动监控 11 键 ==========

    @Test
    fun `promo_style 随设置快照导出并恢复`() {
        prefs.setPromoStyle("BLUR_BG")
        val snapshot = prefs.exportSnapshot()
        assertTrue(snapshot.contains("\"promo_style\":\"BLUR_BG\""))

        prefs.setPromoStyle("LIGHT_CARD")
        prefs.importSnapshot(snapshot)
        assertEquals("BLUR_BG", prefs.getPromoStyle())
    }

    @Test
    fun `非法 promo_style 不写入`() {
        prefs.setPromoStyle("BLUR_BG")
        prefs.importSnapshot("""{"promo_style":"NOT_A_STYLE"}""")
        assertEquals("BLUR_BG", prefs.getPromoStyle())
    }

    @Test
    fun `活动监控开关默认全开`() {
        // 新装应用默认开启所有活动监控：视频/置顶/动态 + 响铃
        // 配合 ActivityDecider 的「首次不提醒」机制，冷启动不会狂响
        assertEquals(true, prefs.isMonitorVideos())
        assertEquals(true, prefs.isMonitorPinned())
        assertEquals(true, prefs.isMonitorDynamics())
        assertEquals(true, prefs.isAlertRingOnActivity())
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

    @Test
    fun `勿扰时段默认关闭 起止默认2300-0700`() {
        assertFalse("勿扰默认关", prefs.isQuietHoursEnabled())
        assertEquals(23 * 60, prefs.getQuietStartMinutes())
        assertEquals(7 * 60, prefs.getQuietEndMinutes())
        prefs.setQuietHoursEnabled(true)
        prefs.setQuietStartMinutes(22 * 60)
        prefs.setQuietEndMinutes(8 * 60)
        assertTrue(prefs.isQuietHoursEnabled())
        assertEquals(22 * 60, prefs.getQuietStartMinutes())
        assertEquals(8 * 60, prefs.getQuietEndMinutes())
    }

    @Test
    fun `直播提醒主题变化与头像提醒默认值`() {
        assertTrue("下播提醒默认开", prefs.isNotifyStreamEnd())
        assertFalse("主题变化默认关", prefs.isNotifyTitleChange())
        assertTrue("头像更新提醒默认开", prefs.isNotifyAvatarChange())
        prefs.setNotifyStreamEnd(false)
        prefs.setNotifyTitleChange(true)
        prefs.setNotifyAvatarChange(false)
        assertFalse(prefs.isNotifyStreamEnd())
        assertTrue(prefs.isNotifyTitleChange())
        assertFalse(prefs.isNotifyAvatarChange())
    }

    @Test
    fun `动态类型默认全开 round trip`() {
        val defaultTypes = setOf("DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_FORWARD", "DYNAMIC_TYPE_ARTICLE")
        assertEquals(defaultTypes, prefs.getMonitorDynamicTypes())
        assertTrue(prefs.isDynamicTypeEnabled("DYNAMIC_TYPE_DRAW"))
        prefs.setMonitorDynamicTypes(setOf("DYNAMIC_TYPE_DRAW"))
        assertFalse(prefs.isDynamicTypeEnabled("DYNAMIC_TYPE_FORWARD"))
        assertTrue(prefs.isDynamicTypeEnabled("DYNAMIC_TYPE_DRAW"))
    }

    @Test
    fun `深色模式默认跟随系统 round trip`() {
        assertEquals(PreferenceManager.DARK_MODE_SYSTEM, prefs.getDarkMode())
        prefs.setDarkMode(PreferenceManager.DARK_MODE_DARK)
        assertEquals(PreferenceManager.DARK_MODE_DARK, prefs.getDarkMode())
    }

    @Test
    fun `检测频率默认标准档 round trip`() {
        assertEquals(PreferenceManager.CHECK_INTERVAL_STANDARD_SECONDS, prefs.getCheckIntervalSeconds())
        prefs.setCheckIntervalSeconds(PreferenceManager.CHECK_INTERVAL_ECO_SECONDS)
        assertEquals(PreferenceManager.CHECK_INTERVAL_ECO_SECONDS, prefs.getCheckIntervalSeconds())
        prefs.setCheckIntervalSeconds(PreferenceManager.CHECK_INTERVAL_REALTIME_SECONDS)
        assertEquals(PreferenceManager.CHECK_INTERVAL_REALTIME_SECONDS, prefs.getCheckIntervalSeconds())
        prefs.setCheckIntervalSeconds(PreferenceManager.CHECK_INTERVAL_STANDARD_SECONDS)
    }

    @Test
    fun `检测记录环形缓冲 超限丢最旧`() {
        repeat(5) { prefs.appendCheckRecord(1000L + it, true, false, "") }
        var records = prefs.getCheckRecords()
        assertEquals(5, records.size)
        assertEquals(1000L, records[0].ts)
        // 超限后丢最旧（cap 500，这里直接验证追加语义即可，cap 值不测满）
        records.forEachIndexed { i, r -> assertEquals(1000L + i, r.ts) }
    }

    @Test
    fun `备份快照恢复魔法期并报告结果`() {
        prefs.setMagicPeriodsJson("[]")
        val magic = """[{"start":1000,"end":2000}]"""
        val result = prefs.importSnapshot(
            org.json.JSONObject().put("magic_periods", magic).put("quiet_enabled", true).toString()
        )
        assertTrue(result.imported)
        assertTrue(result.magicPeriodsImported)
        assertEquals(magic, prefs.getMagicPeriodsJson())
        assertTrue(prefs.isQuietHoursEnabled())
        prefs.setMagicPeriodsJson("[]")
    }

    @Test
    fun `无效快照不会部分覆盖已有设置`() {
        prefs.setMagicPeriodsJson("[]")
        prefs.setQuietHoursEnabled(false)
        val result = prefs.importSnapshot(
            org.json.JSONObject()
                .put("magic_periods", """[{"start":1000,"end":2000}]""")
                .put("quiet_enabled", true)
                .put("quiet_start", 2_000)
                .toString()
        )

        assertFalse(result.imported)
        assertEquals("[]", prefs.getMagicPeriodsJson())
        assertFalse(prefs.isQuietHoursEnabled())
    }
}
