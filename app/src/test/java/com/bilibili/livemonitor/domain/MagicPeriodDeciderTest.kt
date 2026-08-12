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
    fun `segmentPositionOf 孤日 段首 段中 段尾 未标记`() {
        val seg = listOf(MagicPeriod(t0, t0 + 3 * dayMs))
        assertEquals(MagicPeriodDecider.SegmentPosition.FIRST, MagicPeriodDecider.segmentPositionOf(seg, t0))
        assertEquals(MagicPeriodDecider.SegmentPosition.MIDDLE, MagicPeriodDecider.segmentPositionOf(seg, t0 + dayMs))
        assertEquals(MagicPeriodDecider.SegmentPosition.LAST, MagicPeriodDecider.segmentPositionOf(seg, t0 + 2 * dayMs))
        assertEquals(MagicPeriodDecider.SegmentPosition.NONE, MagicPeriodDecider.segmentPositionOf(seg, t0 + 3 * dayMs))
        assertEquals(MagicPeriodDecider.SegmentPosition.NONE, MagicPeriodDecider.segmentPositionOf(seg, t0 - dayMs))
        // 孤日
        val iso = listOf(MagicPeriod(t0 + 10 * dayMs, t0 + 11 * dayMs))
        assertEquals(MagicPeriodDecider.SegmentPosition.ISOLATED, MagicPeriodDecider.segmentPositionOf(iso, t0 + 10 * dayMs))
        // 两段不相邻，边界各自独立
        val two = listOf(seg[0], MagicPeriod(t0 + 5 * dayMs, t0 + 6 * dayMs))
        assertEquals(MagicPeriodDecider.SegmentPosition.ISOLATED, MagicPeriodDecider.segmentPositionOf(two, t0 + 5 * dayMs))
        assertEquals(MagicPeriodDecider.SegmentPosition.LAST, MagicPeriodDecider.segmentPositionOf(two, t0 + 2 * dayMs))
    }

    @Test
    fun `samePeriodCovers 同段相邻为真 跨段相邻为假`() {
        val one = MagicPeriod(t0, t0 + 3 * dayMs)
        val other = MagicPeriod(t0 + 3 * dayMs, t0 + 4 * dayMs)
        val periods = listOf(one, other)
        // 同段内相邻两天
        assertTrue(MagicPeriodDecider.samePeriodCovers(periods, t0, t0 + dayMs))
        // 跨段相邻（t0+2d 属第一段，t0+3d 属第二段——不同段，应留缝）
        assertFalse(MagicPeriodDecider.samePeriodCovers(periods, t0 + 2 * dayMs, t0 + 3 * dayMs))
        // 未标记
        assertFalse(MagicPeriodDecider.samePeriodCovers(periods, t0 + 10 * dayMs, t0 + 11 * dayMs))
    }

    @Test
    fun `segmentDayIndex 段首为1 逐日递增 段外为0`() {
        val periods = listOf(MagicPeriod(t0, t0 + 3 * dayMs))
        assertEquals(1, MagicPeriodDecider.segmentDayIndex(periods, t0))
        assertEquals(2, MagicPeriodDecider.segmentDayIndex(periods, t0 + dayMs))
        assertEquals(3, MagicPeriodDecider.segmentDayIndex(periods, t0 + 2 * dayMs))
        assertEquals(0, MagicPeriodDecider.segmentDayIndex(periods, t0 + 3 * dayMs))
        assertEquals(0, MagicPeriodDecider.segmentDayIndex(periods, t0 - dayMs))
        // 两段相邻不粘连：第二段首日重新从 1 计
        val two = periods + MagicPeriod(t0 + 3 * dayMs, t0 + 5 * dayMs)
        assertEquals(1, MagicPeriodDecider.segmentDayIndex(two, t0 + 3 * dayMs))
        assertEquals(2, MagicPeriodDecider.segmentDayIndex(two, t0 + 4 * dayMs))
    }

    @Test
    fun `monthSegments 月内分段 跨界裁剪 空月为空`() {
        // 假设 t0 所在日 00:00 为 monthStart，构造 31 天月
        val monthStart = t0
        val days = 31
        // 段一：5~7 日；段二：10~11 日；段三：上月 30 日起 3 天 → 当月 1~2 日（裁剪）
        val periods = listOf(
            MagicPeriod(monthStart + 4 * dayMs, monthStart + 7 * dayMs),
            MagicPeriod(monthStart + 9 * dayMs, monthStart + 11 * dayMs),
            MagicPeriod(monthStart - dayMs, monthStart + 2 * dayMs)
        )
        val segments = MagicPeriodDecider.monthSegments(periods, monthStart, days)
        assertEquals(listOf(1 to 2, 5 to 7, 10 to 11), segments)
        // 空月
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            MagicPeriodDecider.monthSegments(periods, monthStart + 40 * dayMs, 30)
        )
        // 相邻不同段不粘连
        val adjacent = listOf(
            MagicPeriod(monthStart + 4 * dayMs, monthStart + 6 * dayMs),
            MagicPeriod(monthStart + 6 * dayMs, monthStart + 8 * dayMs)
        )
        assertEquals(
            listOf(5 to 6, 7 to 8),
            MagicPeriodDecider.monthSegments(adjacent, monthStart, days)
        )
    }

    @Test
    fun `segmentPositionOf 不同段相邻时各自成段 不粘连`() {
        // 两段紧贴：A=[t0, t0+2d)，B=[t0+2d, t0+4d) —— 相邻但不同段
        val periods = listOf(
            MagicPeriod(t0, t0 + 2 * dayMs),
            MagicPeriod(t0 + 2 * dayMs, t0 + 4 * dayMs)
        )
        // A 的末日在 B 开始处交界 → A 末日应为 LAST（不与 B 粘连）
        assertEquals(MagicPeriodDecider.SegmentPosition.LAST,
            MagicPeriodDecider.segmentPositionOf(periods, t0 + dayMs))
        // B 的第二天 → FIRST
        assertEquals(MagicPeriodDecider.SegmentPosition.FIRST,
            MagicPeriodDecider.segmentPositionOf(periods, t0 + 2 * dayMs))
        // B 末日 → LAST
        assertEquals(MagicPeriodDecider.SegmentPosition.LAST,
            MagicPeriodDecider.segmentPositionOf(periods, t0 + 3 * dayMs))
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
