package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSearchTest {

    private val sessions = listOf(
        StreamSessionEntity(startTs = 1000, endTs = 2000, title = "SC2 肉鸽之夜"),
        StreamSessionEntity(startTs = 3000, endTs = 4000, title = "闲聊杂谈"),
        StreamSessionEntity(startTs = 5000, endTs = 6000, title = null)
    )
    private val moods = listOf(
        MoodEventEntity(eventTs = 2500, mood = "happy", title = "看了肉鸽", reason = "好玩", note = null, createdAt = 0),
        MoodEventEntity(eventTs = 4500, mood = "sad", title = "失眠", reason = "想她", note = "深夜", createdAt = 0)
    )

    @Test
    fun `按场次标题匹配`() {
        val hits = SessionSearch.search(sessions, moods, "肉鸽")
        assertEquals(2, hits.size) // 场次 + 心情「看了肉鸽」都中
        assertEquals(SessionSearch.Kind.MOOD, hits[0].kind) // 2500 > 1000 倒序
        assertEquals(SessionSearch.Kind.SESSION, hits[1].kind)
    }

    @Test
    fun `按心情原因备注匹配`() {
        assertEquals(1, SessionSearch.search(sessions, moods, "想她").size)
        assertEquals(1, SessionSearch.search(sessions, moods, "深夜").size)
        // 心情 display 文案也可命中
        assertEquals(1, SessionSearch.search(sessions, moods, "开心").size)
    }

    @Test
    fun `大小写不敏感与空查询`() {
        assertEquals(1, SessionSearch.search(sessions, moods, "sc2").size)
        assertEquals(0, SessionSearch.search(sessions, moods, "  ").size)
        assertEquals(0, SessionSearch.search(sessions, moods, "不存在").size)
    }
}
