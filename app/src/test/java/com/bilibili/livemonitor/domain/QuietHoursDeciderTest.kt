package com.bilibili.livemonitor.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursDeciderTest {

    @Test
    fun `开关关闭 恒非勿扰`() {
        assertFalse(QuietHoursDecider.isInQuietHours(0, 23 * 60, 7 * 60, enabled = false))
        assertFalse(QuietHoursDecider.isInQuietHours(3 * 60, 23 * 60, 7 * 60, enabled = false))
    }

    @Test
    fun `同日区间 命中与未命中`() {
        // 10:00 → 18:00
        assertTrue(QuietHoursDecider.isInQuietHours(10 * 60, 10 * 60, 18 * 60, enabled = true))
        assertTrue(QuietHoursDecider.isInQuietHours(17 * 60 + 59, 10 * 60, 18 * 60, enabled = true))
        assertFalse(QuietHoursDecider.isInQuietHours(9 * 60 + 59, 10 * 60, 18 * 60, enabled = true))
        assertFalse(QuietHoursDecider.isInQuietHours(18 * 60, 10 * 60, 18 * 60, enabled = true))
        // 结束时刻本身不算勿扰
        assertFalse(QuietHoursDecider.isInQuietHours(18 * 60, 10 * 60, 18 * 60, enabled = true))
    }

    @Test
    fun `跨午夜区间 边界命中`() {
        // 23:00 → 07:00
        assertTrue(QuietHoursDecider.isInQuietHours(23 * 60, 23 * 60, 7 * 60, enabled = true))
        assertTrue(QuietHoursDecider.isInQuietHours(0, 23 * 60, 7 * 60, enabled = true))
        assertTrue(QuietHoursDecider.isInQuietHours(6 * 60 + 59, 23 * 60, 7 * 60, enabled = true))
        assertFalse(QuietHoursDecider.isInQuietHours(7 * 60, 23 * 60, 7 * 60, enabled = true))
        assertFalse(QuietHoursDecider.isInQuietHours(12 * 60, 23 * 60, 7 * 60, enabled = true))
        assertFalse(QuietHoursDecider.isInQuietHours(22 * 60 + 59, 23 * 60, 7 * 60, enabled = true))
    }

    @Test
    fun `起止相同为空区间`() {
        assertFalse(QuietHoursDecider.isInQuietHours(12 * 60, 12 * 60, 12 * 60, enabled = true))
    }
}
