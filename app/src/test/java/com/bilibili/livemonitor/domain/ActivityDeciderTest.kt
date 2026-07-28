package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动提醒决策（纯函数，无 Android 依赖）。
 * 核心原则：首次不提醒（lastAid/lastId=null 时不触发）。
 */
class ActivityDeciderTest {

    // ---------- shouldAlertVideo ----------

    @Test
    fun `首次检测 lastAid=null 不提醒`() {
        // 场景：App 新装后第一次检测，只记录当前最新 avid，不触发提醒
        assertFalse(ActivityDecider.shouldAlertVideo(newAid = 100L, lastAid = null))
    }

    @Test
    fun `aid 变化 提醒`() {
        assertTrue(ActivityDecider.shouldAlertVideo(newAid = 101L, lastAid = 100L))
    }

    @Test
    fun `aid 不变 不提醒`() {
        // 场景：60s 周期连续检测同一视频，不重复提醒
        assertFalse(ActivityDecider.shouldAlertVideo(newAid = 100L, lastAid = 100L))
    }

    // ---------- shouldAlertPinned ----------

    @Test
    fun `首次检测置顶 lastAid=null 不提醒`() {
        assertFalse(ActivityDecider.shouldAlertPinned(newAid = 200L, lastAid = null))
    }

    @Test
    fun `置顶变化 提醒`() {
        assertTrue(ActivityDecider.shouldAlertPinned(newAid = 201L, lastAid = 200L))
    }

    @Test
    fun `置顶取消 newAid=null 也算变化 提醒`() {
        // 场景：UP 主取消了置顶，从有变无也算变化
        assertTrue(ActivityDecider.shouldAlertPinned(newAid = null, lastAid = 200L))
    }

    @Test
    fun `置顶不变 不提醒`() {
        assertFalse(ActivityDecider.shouldAlertPinned(newAid = 200L, lastAid = 200L))
    }

    // ---------- shouldAlertDynamic ----------

    @Test
    fun `首次检测动态 lastId=null 不提醒`() {
        assertFalse(ActivityDecider.shouldAlertDynamic(newId = "dyn123", lastId = null))
    }

    @Test
    fun `动态 id 变化 提醒`() {
        assertTrue(ActivityDecider.shouldAlertDynamic(newId = "dyn124", lastId = "dyn123"))
    }

    @Test
    fun `动态 id 不变 不提醒`() {
        assertFalse(ActivityDecider.shouldAlertDynamic(newId = "dyn123", lastId = "dyn123"))
    }

    // ---------- longToNullable / stringToNullable ----------

    @Test
    fun `longToNullable 负数转 null`() {
        assertNull(ActivityDecider.longToNullable(-1L))
        assertNull(ActivityDecider.longToNullable(-100L))
    }

    @Test
    fun `longToNullable 非负数保留`() {
        assertEquals(0L, ActivityDecider.longToNullable(0L))
        assertEquals(100L, ActivityDecider.longToNullable(100L))
    }

    @Test
    fun `stringToNullable 空白转 null`() {
        assertNull(ActivityDecider.stringToNullable(""))
        assertNull(ActivityDecider.stringToNullable("   "))
    }

    @Test
    fun `stringToNullable 非空保留`() {
        assertEquals("dyn123", ActivityDecider.stringToNullable("dyn123"))
    }
}
