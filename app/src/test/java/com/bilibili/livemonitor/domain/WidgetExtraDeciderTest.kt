package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.StreamSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class WidgetExtraDeciderTest {

    private fun nowAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `今日已播含进行中部分`() {
        val now = nowAt(22, 30)
        val sessions = listOf(
            StreamSessionEntity(startTs = nowAt(9, 0), endTs = nowAt(11, 30)),  // 2.5h 闭合
            StreamSessionEntity(startTs = nowAt(21, 0), endTs = nowAt(21, 30))  // 0.5h 闭合
        )
        val line = WidgetExtraDecider.extraLine(sessions, now)!!
        assert(line.startsWith("今日已播 3小时0分")) { line }
        assert(line.contains("上次开播 1 小时前")) { line }
    }

    @Test
    fun `最新场次进行中时 不显示上次开播`() {
        val now = nowAt(22, 0)
        val sessions = listOf(
            StreamSessionEntity(startTs = nowAt(21, 0), endTs = null) // 直播中
        )
        val line = WidgetExtraDecider.extraLine(sessions, now)!!
        assert(line.startsWith("今日已播 1小时0分")) { line }
        assert(!line.contains("上次开播")) { line }
    }

    @Test
    fun `今日无场次不显示已播 上次开播按天`() {
        val now = nowAt(12, 0)
        val twoDaysAgo = now - 2 * 86_400_000L
        val sessions = listOf(
            StreamSessionEntity(startTs = twoDaysAgo, endTs = twoDaysAgo + 3_600_000)
        )
        assertEquals("上次开播 2 天前", WidgetExtraDecider.extraLine(sessions, now))
    }

    @Test
    fun `空数据返回 null`() {
        assertNull(WidgetExtraDecider.extraLine(emptyList(), nowAt(12, 0)))
    }

    @Test
    fun `分钟级今日已播`() {
        val now = nowAt(20, 0)
        val sessions = listOf(
            StreamSessionEntity(startTs = nowAt(19, 0), endTs = nowAt(19, 45))
        )
        val line = WidgetExtraDecider.extraLine(sessions, now)!!
        assert(line.contains("今日已播 45分钟")) { line }
    }
}
