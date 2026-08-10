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

    @Test
    fun `dailyCounts 7 天桶对齐 越界忽略`() {
        // now = epoch day 1,000,000（UTC 对齐）；桶下标 0 = 6 天前
        val sessions = listOf(
            closed(0, -3_600_000),                 // 今天
            closed(1 * day, 1 * day - 3_600_000),  // 昨天
            closed(6 * day, 6 * day - 3_600_000),  // 6 天前（最后桶）
            closed(7 * day, 7 * day - 3_600_000),  // 7 天前（越界）
            StreamSessionEntity(startTs = now - day, endTs = null) // 未闭合忽略
        )
        val daily = StreamStats.dailyCounts(sessions, now, 7)
        assertEquals(listOf(1, 0, 0, 0, 0, 1, 1), daily)
    }

    @Test
    fun `weekdayLabels 与 dailyCounts 桶逐日对齐`() {
        // now = epoch day 1,000,000（UTC）：今天星期 = (1_000_000+4)%7 = 5
        val labels = StreamStats.weekdayLabels(now, 7)
        assertEquals(listOf(6, 0, 1, 2, 3, 4, 5), labels)
        // 昨天一场：命中桶的标签必须等于该场次实际星期，而不是偏一天
        val yesterday = closed(1 * day, 1 * day - 3_600_000)
        val daily = StreamStats.dailyCounts(listOf(yesterday), now, 7)
        val hitIdx = daily.indexOfFirst { it > 0 }
        val expectedWd = ((yesterday.startTs / day + 4) % 7).toInt()
        assertEquals(expectedWd, labels[hitIdx])
    }

    @Test
    fun `weekdayLabels 带时区偏移 周日场次标在周日桶`() {
        // 回归：2026-08-10 周一 20:00(+8) 查看，8-09 周日那场必须落在「周日」标签的桶
        val offset = 8 * 3_600_000L
        val mondayEpochDay = 20_675L // 2026-08-10 周一
        val nowLocal = mondayEpochDay * day - offset + 20 * 3_600_000L
        val sundayStart = (mondayEpochDay - 1) * day - offset + 20 * 3_600_000L + 27 * 60_000L
        val sunday = StreamSessionEntity(startTs = sundayStart, endTs = sundayStart + 2 * 3_600_000L)

        val daily = StreamStats.dailyCounts(listOf(sunday), nowLocal, 7, offset)
        val labels = StreamStats.weekdayLabels(nowLocal, 7, offset)
        val hitIdx = daily.indexOfFirst { it > 0 }
        assertEquals("周日的场次应标在周日桶", 0, labels[hitIdx])
        assertEquals("最后一桶是今天（周一）", 1, labels[6])
    }

    @Test
    fun `favoriteWeekday 累计并返回最多的一天`() {
        // 1970-01-01 周四（epochDay 0 → 4）；+day 周五（5）；+2day 周六（6）
        val thursday = StreamSessionEntity(startTs = 0, endTs = 3_600_000)
        val friday = StreamSessionEntity(startTs = day, endTs = day + 3_600_000)
        val saturday = StreamSessionEntity(startTs = 2 * day, endTs = 2 * day + 3_600_000)
        val fav = StreamStats.favoriteWeekday(listOf(thursday, thursday, friday, saturday))!!
        assertEquals(4 to 2, fav)
        assertEquals("空列表应返回 null", null, StreamStats.favoriteWeekday(emptyList()))
        assertEquals(
            "未闭合不参与",
            null,
            StreamStats.favoriteWeekday(listOf(StreamSessionEntity(startTs = 0, endTs = null)))
        )
    }
}
