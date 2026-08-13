package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorHealthTest {

    private val now = 1_000_000_000_000L

    private fun ok(ts: Long, live: Boolean = false) =
        MonitorHealth.CheckRecord(ts, true, live, "")

    private fun err(ts: Long, reason: String) =
        MonitorHealth.CheckRecord(ts, false, false, reason)

    @Test
    fun `空记录全零`() {
        val s = MonitorHealth.summarize(emptyList(), now)
        assertEquals(0, s.totalChecks)
        assertEquals(0, s.avgIntervalMs)
        assertNull(s.lastCheckTs)
    }

    @Test
    fun `24h 窗口过滤 成功率 原因 top 间隔`() {
        val records = listOf(
            ok(now - 25 * 3_600_000L),                    // 窗口外
            ok(now - 60_000),                              // 3min 前
            ok(now - 120_000, live = true),                // 2min 前
            err(now - 180_000, "api network error"),       // 3min 前
            err(now - 240_000, "api network error"),       // 4min 前
            err(now - 300_000, "check timeout")            // 5min 前
        )
        val s = MonitorHealth.summarize(records, now)
        assertEquals(5, s.totalChecks)      // 窗口外不算
        assertEquals(2, s.successChecks)
        assertEquals(1, s.liveChecks)
        assertEquals(60_000L, s.avgIntervalMs) // 相邻 60s
        assertEquals("api network error", s.topReasons[0].first)
        assertEquals(2, s.topReasons[0].second)
        assertEquals(now - 60_000, s.lastCheckTs)
    }

    @Test
    fun `单条记录间隔为 0`() {
        val s = MonitorHealth.summarize(listOf(ok(now)), now)
        assertEquals(1, s.totalChecks)
        assertEquals(0L, s.avgIntervalMs)
    }
}
