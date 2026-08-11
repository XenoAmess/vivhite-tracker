package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodTimingTest {

    @Test
    fun `endTs 开始加时长`() {
        assertEquals(1_000L + 90 * 60_000L, MoodTiming.endTs(1_000L, 90))
        assertEquals(1_000L, MoodTiming.endTs(1_000L, 0))
    }

    @Test
    fun `durationMinFromEnd 正常差值`() {
        assertEquals(90, MoodTiming.durationMinFromEnd(0L, 90 * 60_000L))
    }

    @Test
    fun `durationMinFromEnd 结束不晚于开始视为跨午夜`() {
        // 23:30 开始，结束选 00:30 → 60 分钟（次日）
        val day = 86_400_000L
        val start = day + 23 * 3_600_000L + 30 * 60_000L
        val endSameDay = day + 30 * 60_000L
        assertEquals(60, MoodTiming.durationMinFromEnd(start, endSameDay))
        // 结束 == 开始 → 视为 24h
        assertEquals(24 * 60, MoodTiming.durationMinFromEnd(start, start))
    }
}
