package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.api.BilibiliApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个用例对应一个真实用户场景（多数来自真机实测事件）。
 */
class LiveStateDeciderTest {

    // ---------- shouldAlert ----------

    @Test
    fun `首次检查就在播 立即提醒`() {
        // 场景：用户打开监控时主播已经在播，应当立刻告知
        assertTrue(LiveStateDecider.shouldAlert(lastStatus = null, isLive = true))
    }

    @Test
    fun `首次检查未开播 不提醒`() {
        assertFalse(LiveStateDecider.shouldAlert(lastStatus = null, isLive = false))
    }

    @Test
    fun `未开播到开播跳变 提醒`() {
        // 场景：核心功能——监控中发现开播
        assertTrue(LiveStateDecider.shouldAlert(lastStatus = false, isLive = true))
    }

    @Test
    fun `开播到未开播 不提醒`() {
        assertFalse(LiveStateDecider.shouldAlert(lastStatus = true, isLive = false))
    }

    @Test
    fun `持续在播 不重复提醒`() {
        // 场景：60 秒周期检测连续命中"在播"，不能每分钟都响铃
        assertFalse(LiveStateDecider.shouldAlert(lastStatus = true, isLive = true))
    }

    // ---------- restoreLastStatus（真机事件：2026-07-23 实测发现重复提醒 bug） ----------

    @Test
    fun `直播中进程被杀重启 状态恢复后不重复提醒`() {
        // 场景（真机实测）：直播中 SIGKILL → 服务自动重启 → lastStatus 若为 null 会误触首次提醒。
        // 修复后：10 分钟内状态恢复为 true，shouldAlert(true, true) = false，不重复响铃
        val restored = LiveStateDecider.restoreLastStatus(
            lastCheckTime = 1000L,
            lastCheckSuccess = true,
            lastCheckLive = true,
            now = 1000L + 5 * 60_000L, // 5 分钟前检测过
            maxAgeMillis = 600_000L
        )
        assertEquals(true, restored)
        assertFalse(LiveStateDecider.shouldAlert(restored, isLive = true))
    }

    @Test
    fun `停播时进程重启 恢复未开播 之后开播正常提醒`() {
        val restored = LiveStateDecider.restoreLastStatus(
            lastCheckTime = 1000L,
            lastCheckSuccess = true,
            lastCheckLive = false,
            now = 1000L + 60_000L,
            maxAgeMillis = 600_000L
        )
        assertEquals(false, restored)
        assertTrue(LiveStateDecider.shouldAlert(restored, isLive = true))
    }

    @Test
    fun `状态超过10分钟过期 视为首次 开播重新提醒`() {
        // 场景：进程死亡超过 10 分钟，期间主播可能刚开播，重新提醒是正确行为
        val restored = LiveStateDecider.restoreLastStatus(
            lastCheckTime = 1000L,
            lastCheckSuccess = true,
            lastCheckLive = false,
            now = 1000L + 600_001L, // 刚好超过窗口
            maxAgeMillis = 600_000L
        )
        assertNull(restored)
        assertTrue(LiveStateDecider.shouldAlert(restored, isLive = true))
    }

    @Test
    fun `状态恰好在10分钟边界内 仍然恢复`() {
        val restored = LiveStateDecider.restoreLastStatus(
            lastCheckTime = 1000L,
            lastCheckSuccess = true,
            lastCheckLive = true,
            now = 1000L + 600_000L, // 恰好等于窗口，不过期
            maxAgeMillis = 600_000L
        )
        assertEquals(true, restored)
    }

    @Test
    fun `上次检测失败 不恢复状态`() {
        // 场景：上次是网络 Error（success=false），状态不可信，不能恢复
        val restored = LiveStateDecider.restoreLastStatus(
            lastCheckTime = 1000L,
            lastCheckSuccess = false,
            lastCheckLive = true,
            now = 2000L,
            maxAgeMillis = 600_000L
        )
        assertNull(restored)
    }

    @Test
    fun `从未有过检测记录 不恢复状态`() {
        val restored = LiveStateDecider.restoreLastStatus(
            lastCheckTime = 0L,
            lastCheckSuccess = true,
            lastCheckLive = true,
            now = 2000L,
            maxAgeMillis = 600_000L
        )
        assertNull(restored)
    }

    // ---------- shouldRetry（真机场景：Doze 下网络不可达） ----------

    @Test
    fun `网络错误 需要重试`() {
        // 场景：Doze 下网络不可达导致 Error，15 秒后应重试一次而不是等下个周期
        assertTrue(LiveStateDecider.shouldRetry(BilibiliApi.LiveStatus.Error("timeout")))
    }

    @Test
    fun `确定的在播结果 不重试`() {
        assertFalse(LiveStateDecider.shouldRetry(BilibiliApi.LiveStatus.Live))
    }

    @Test
    fun `确定的未开播结果 不重试`() {
        assertFalse(LiveStateDecider.shouldRetry(BilibiliApi.LiveStatus.NotLive))
    }
}
