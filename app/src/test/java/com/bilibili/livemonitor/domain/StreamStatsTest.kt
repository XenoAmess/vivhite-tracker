package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.StreamSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatsTest {

    private val day = 86_400_000L
    private val now = 1_000_000L * day

    private fun closed(startOffsetMs: Long, endOffsetMs: Long) =
        StreamSessionEntity(startTs = now - startOffsetMs, endTs = now - endOffsetMs)

    @Test
    fun `空列表 全零`() {
        val s = StreamStats.summarize(emptyList(), now)
        assertEquals(0, s.weekCount)
        assertEquals(0, s.monthCount)
        assertEquals(0, s.avgDurationMs)
        assertEquals(0, s.maxDurationMs)
    }

    @Test
    fun `未闭合场次不参与统计`() {
        val open = StreamSessionEntity(startTs = now - day, endTs = null)
        val s = StreamStats.summarize(listOf(open), now)
        assertEquals(0, s.weekCount)
        assertEquals(0, s.totalDurationMs)
    }

    @Test
    fun `周月计数 平均与最长`() {
        // 3 天前(本周内) 2h；10 天前(本月内非本周) 1h；40 天前(超本月) 30min
        val sessions = listOf(
            closed(3 * day, 3 * day - 2 * 3_600_000), // 2h
            closed(10 * day, 10 * day - 3_600_000),   // 1h
            closed(40 * day, 40 * day - 30 * 60_000)  // 30min
        )
        val s = StreamStats.summarize(sessions, now)
        assertEquals(1, s.weekCount)
        assertEquals(2, s.monthCount)
        // avg/max 覆盖全部已闭合场次（含 40 天前的 30min）
        assertEquals((2 * 3_600_000 + 3_600_000 + 30 * 60_000) / 3, s.avgDurationMs)
        assertEquals(2 * 3_600_000, s.maxDurationMs)
        assertEquals(2 * 3_600_000 + 3_600_000 + 30 * 60_000, s.totalDurationMs)
    }
}
