package com.bilibili.livemonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParserTest {

    @Test
    fun `多段解析 tag 日期 提交行`() {
        val text = """
            ## v1.6.0 (2026-08-02)
            d0f09c9 fix(service): 观播静音卡死修复
            b045247 feat(magic): 魔法期分享图双主题立绘版

            ## v1.5.1 (2026-07-30)
            aaa1111 fix(x): a
        """.trimIndent()
        val notes = ChangelogParser.parse(text)
        assertEquals(2, notes.size)
        assertEquals("v1.6.0", notes[0].tag)
        assertEquals("2026-08-02", notes[0].date)
        assertEquals(2, notes[0].lines.size)
        assertEquals("d0f09c9 fix(service): 观播静音卡死修复", notes[0].lines[0])
        assertEquals("v1.5.1", notes[1].tag)
        assertEquals(1, notes[1].lines.size)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(ChangelogParser.parse("").isEmpty())
        assertTrue(ChangelogParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `兜底文案无段头返回空列表`() {
        assertTrue(ChangelogParser.parse("暂无历史版本日志").isEmpty())
    }

    @Test
    fun `乱格式行跳过不崩`() {
        val text = """
            ## v1.6.0 (2026-08-02)
            d0f09c9 fix: ok
            ## 这不是段头但含双井号在行中 ##
            b045247 feat: next
        """.trimIndent()
        val notes = ChangelogParser.parse(text)
        assertEquals(1, notes.size)
        // "## 这不是段头但含双井号在行中 ##" 不匹配 HEADER 正则被当普通行收进 lines
        assertEquals(3, notes[0].lines.size)
    }

    @Test
    fun `段头无日期也能解析`() {
        val notes = ChangelogParser.parse("## v9.9.9 ()\nabc1234 x: y")
        assertEquals(1, notes.size)
        assertEquals("v9.9.9", notes[0].tag)
        assertEquals("", notes[0].date)
    }
}
