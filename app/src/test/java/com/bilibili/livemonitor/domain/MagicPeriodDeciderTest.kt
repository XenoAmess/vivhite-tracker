package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicPeriodDeciderTest {

    private val dayMs = 24L * 3600 * 1000
    private val t0 = 1_700_000_000_000L

    @Test
    fun `imageText 最新未结束显示死了啦 已结束或无记录显示复活吧`() {
        assertEquals("死了啦，都怪你~", MagicPeriodDecider.imageText(t0 + dayMs, t0))
        assertEquals("复活吧，我的爱人！", MagicPeriodDecider.imageText(t0 - 1, t0))
        assertEquals("复活吧，我的爱人！", MagicPeriodDecider.imageText(null, t0))
    }

    @Test
    fun `computeEnd 按天精确加算`() {
        assertEquals(t0 + 3 * dayMs, MagicPeriodDecider.computeEnd(t0, 3))
        assertEquals(t0 + dayMs, MagicPeriodDecider.computeEnd(t0, 1))
    }

    @Test
    fun `computeDurationDays 与 computeEnd 互逆 且不足一天进位`() {
        assertEquals(3, MagicPeriodDecider.computeDurationDays(t0, MagicPeriodDecider.computeEnd(t0, 3)))
        assertEquals(1, MagicPeriodDecider.computeDurationDays(t0, t0 + 1000))
        assertEquals(1, MagicPeriodDecider.computeDurationDays(t0, t0 - 1000))
    }

    @Test
    fun `nextPendingEnd 取最近未来结束 全过去或空表返回null`() {
        val periods = listOf(
            MagicPeriod(t0 - 10 * dayMs, t0 - 5 * dayMs),
            MagicPeriod(t0, t0 + 2 * dayMs),
            MagicPeriod(t0 + 10 * dayMs, t0 + 12 * dayMs)
        )
        assertEquals(t0 + 2 * dayMs, MagicPeriodDecider.nextPendingEnd(periods, t0))
        assertNull(MagicPeriodDecider.nextPendingEnd(emptyList(), t0))
        assertNull(MagicPeriodDecider.nextPendingEnd(listOf(periods[0]), t0))
    }

    @Test
    fun `toggleDay 未标记新增三天段 已标记删除整段`() {
        val added = MagicPeriodDecider.toggleDay(emptyList(), t0)
        assertEquals(1, added.size)
        assertEquals(MagicPeriod(t0, t0 + 3 * dayMs), added[0])

        // 点段内第二天 → 删除整段
        val removed = MagicPeriodDecider.toggleDay(added, t0 + dayMs)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `isDayMarked 覆盖判定`() {
        val periods = listOf(MagicPeriod(t0, t0 + 3 * dayMs))
        assertTrue(MagicPeriodDecider.isDayMarked(periods, t0 + dayMs))
        assertFalse(MagicPeriodDecider.isDayMarked(periods, t0 + 4 * dayMs))
        assertFalse(MagicPeriodDecider.isDayMarked(periods, t0 - dayMs))
    }

    @Test
    fun `updateStart 保持时长 updateDuration 保持开始 updateEnd 重算时长`() {
        val periods = listOf(MagicPeriod(t0, t0 + 3 * dayMs))
        val moved = MagicPeriodDecider.updateStart(periods, 0, t0 + dayMs)
        assertEquals(MagicPeriod(t0 + dayMs, t0 + 4 * dayMs), moved[0])

        val longer = MagicPeriodDecider.updateDuration(periods, 0, 5)
        assertEquals(t0 + 5 * dayMs, longer[0].end)

        val newEnd = MagicPeriodDecider.updateEnd(periods, 0, t0 + 7 * dayMs)
        assertEquals(t0 + 7 * dayMs, newEnd[0].end)
        // 结束早于开始 → 兜底为开始+1天
        val invalid = MagicPeriodDecider.updateEnd(periods, 0, t0 - dayMs)
        assertEquals(t0 + dayMs, invalid[0].end)
    }
}
