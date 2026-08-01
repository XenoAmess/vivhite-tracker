package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享文案决策：开播/未开播 × 有无直播标题。
 * 守护 2026-08 用户需求：没开播时分享内容必须体现"还没开播，期待开播"。
 */
class ShareTextDeciderTest {

    private val roomId = 11258892L

    @Test
    fun `开播且有直播标题 标题用直播标题`() {
        assertEquals("「失眠 无言」", ShareTextDecider.title(true, "失眠 无言"))
    }

    @Test
    fun `开播但无直播标题 标题兜底开播啦`() {
        assertEquals("白绮开播啦！", ShareTextDecider.title(true, null))
        assertEquals("白绮开播啦！", ShareTextDecider.title(true, ""))
    }

    @Test
    fun `未开播 标题固定为还没开播且不误报`() {
        val title = ShareTextDecider.title(false, "旧标题")
        assertEquals("白绮还没开播", title)
        assertFalse(title.contains("开播啦"))
    }

    @Test
    fun `开播 摘要为正在直播`() {
        assertEquals("白绮正在直播 · 11258892", ShareTextDecider.summary(true, roomId))
    }

    @Test
    fun `未开播 摘要为还没开播`() {
        assertEquals("白绮还没开播 · 11258892", ShareTextDecider.summary(false, roomId))
    }

    @Test
    fun `开播且有标题 正文带标题与房间号`() {
        val body = ShareTextDecider.body(true, roomId, "失眠 无言")
        assertTrue(body.contains("正在直播"))
        assertTrue(body.contains("11258892"))
        assertTrue(body.contains("失眠 无言"))
    }

    @Test
    fun `开播但无标题 正文走硬编码`() {
        assertTrue(ShareTextDecider.body(true, roomId, null).contains("快来看"))
    }

    @Test
    fun `未开播 正文体现蹲开播意向且不带旧标题`() {
        val body = ShareTextDecider.body(false, roomId, "旧标题")
        assertEquals("白绮还没开播，先来直播间蹲一个开播！", body)
        assertFalse(body.contains("旧标题"))
        assertFalse(body.contains("正在直播"))
    }
}
