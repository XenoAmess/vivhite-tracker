package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class SessionBackupTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun ts(s: String): Long = fmt.parse(s)!!.time

    @Test
    fun `混合导出导入往返 含逗号引号换行中文`() {
        val sessions = listOf(
            StreamSessionEntity(startTs = ts("2026-08-09 20:27"), endTs = ts("2026-08-09 23:01"), title = "sad, \"emo\"\n换行"),
            StreamSessionEntity(startTs = ts("2026-08-10 12:00"), endTs = null, title = null) // 进行中
        )
        val moods = listOf(
            MoodEventEntity(
                eventTs = ts("2026-08-09 21:00"), durationMin = 90, mood = "happy",
                title = "看了场直播，很开心", reason = "她唱了我点的歌", note = "下次\"还点\"",
                createdAt = 0
            ),
            MoodEventEntity(
                eventTs = ts("2026-08-10 08:00"), durationMin = 0, mood = "calm",
                title = "早起", reason = null, note = null, createdAt = 0
            )
        )
        val csv = SessionBackup.toCsv(sessions, moods)
        assertTrue(csv.startsWith(SessionBackup.HEADER))

        val parsed = SessionBackup.parse(csv)
        // 进行中场次导入时跳过（skipped 计数），闭合场次回来
        assertEquals(1, parsed.sessions.size)
        assertEquals(ts("2026-08-09 20:27"), parsed.sessions[0].startTs)
        assertEquals(ts("2026-08-09 23:01"), parsed.sessions[0].endTs)
        assertEquals("sad, \"emo\"\n换行", parsed.sessions[0].title)

        assertEquals(2, parsed.moods.size)
        val m0 = parsed.moods[0]
        assertEquals(ts("2026-08-09 21:00"), m0.eventTs)
        assertEquals(90, m0.durationMin)
        assertEquals("happy", m0.mood) // display「😄开心」反查回 key
        assertEquals("看了场直播，很开心", m0.title)
        assertEquals("她唱了我点的歌", m0.reason)
        assertEquals("下次\"还点\"", m0.note)
        assertEquals(0, parsed.moods[1].durationMin)
        assertEquals("calm", parsed.moods[1].mood)
        assertEquals(1, parsed.skippedLines) // 进行中场次
    }

    @Test
    fun `心情反查 display 裸key 未知原文`() {
        assertEquals("happy", MoodCatalog.keyOf("😄开心"))
        assertEquals("happy", MoodCatalog.keyOf("happy"))
        assertEquals("自定义心情", MoodCatalog.keyOf("自定义心情"))
    }

    @Test
    fun `旧格式导出文件按纯场次解析`() {
        // 旧格式：表头 5 列无「类型」，正文 4 列（首列「场次」只在表头）
        val old = "场次,开始,结束,时长(分钟),标题\n" +
            "2026-08-09 20:27,2026-08-09 23:01,154,\"sad\"\n" +
            "2026-08-10 12:00,进行中,,\"直播中\"\n"
        val parsed = SessionBackup.parse(old)
        assertEquals(1, parsed.sessions.size)
        assertEquals(0, parsed.moods.size)
        assertEquals(ts("2026-08-09 20:27"), parsed.sessions[0].startTs)
        assertEquals("sad", parsed.sessions[0].title)
        assertEquals(1, parsed.skippedLines) // 进行中
    }

    @Test
    fun `旧格式 5 列变体也兼容`() {
        val old = "场次,开始,结束,时长(分钟),标题\n" +
            "场次,2026-08-09 20:27,2026-08-09 23:01,154,\"sad\"\n"
        val parsed = SessionBackup.parse(old)
        assertEquals(1, parsed.sessions.size)
        assertEquals("sad", parsed.sessions[0].title)
    }

    @Test
    fun `坏行计入 skipped 不炸`() {
        val csv = SessionBackup.HEADER + "\n" +
            "场次,不是日期,2026-08-09 23:01,154,\"x\",,,\n" +
            "心情,2026-08-09 21:00,,0,\"\",😄开心,,\n" + // 空标题
            "未知类型,2026-08-09 21:00,,0,\"t\",,,\n" +
            "心情,2026-08-09 21:00,,0,\"正常\",😄开心,,\n"
        val parsed = SessionBackup.parse(csv)
        assertEquals(0, parsed.sessions.size)
        assertEquals(1, parsed.moods.size)
        assertEquals(3, parsed.skippedLines)
    }

    @Test
    fun `空文本返回空结果`() {
        val parsed = SessionBackup.parse("")
        assertEquals(0, parsed.sessions.size)
        assertEquals(0, parsed.moods.size)
        assertEquals(0, parsed.skippedLines)
    }
}
