package com.bilibili.livemonitor.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReminderDeciderTest {

    private val now = 1_000_000_000L

    @Test
    fun `预告时间在窗口内且未提醒过 提醒`() {
        assertTrue(LiveReminderDecider.shouldRemind(now + 3_600_000, now, null, "dyn1"))
    }

    @Test
    fun `已过或刚好到点 不提醒`() {
        assertFalse(LiveReminderDecider.shouldRemind(now, now, null, "dyn1"))
        assertFalse(LiveReminderDecider.shouldRemind(now - 1000, now, null, "dyn1"))
    }

    @Test
    fun `超出24小时窗口 不提醒`() {
        assertFalse(LiveReminderDecider.shouldRemind(now + 24L * 3_600_000 + 1000, now, null, "dyn1"))
    }

    @Test
    fun `同一预告已提醒 去重`() {
        assertFalse(LiveReminderDecider.shouldRemind(now + 3_600_000, now, "dyn1", "dyn1"))
    }

    @Test
    fun `时间为null 不提醒`() {
        assertFalse(LiveReminderDecider.shouldRemind(null, now, null, "dyn1"))
    }
}
