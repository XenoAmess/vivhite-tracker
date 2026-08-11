package com.bilibili.livemonitor.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatsImageRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sampleData(records: List<StatsImageRenderer.RecordLine>) =
        StatsImageRenderer.StatsImageData(
            monthTitle = "2026年8月",
            summaryLines = listOf("本周 5 场 · 本月 5 场", "平均 2小时27分 · 最长 4小时0分", "常播：周日"),
            barCounts = listOf(0, 1, 1, 1, 1, 1, 0),
            barLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"),
            leading = 5, // 2026-08-01 周六 → 周日=0 起 6 格
            daysInMonth = 31,
            sessionDays = setOf(5, 6, 7, 8, 9),
            todayDom = 10,
            moodStats = listOf("😄开心" to 6, "😢难过" to 2),
            records = records,
            exportDate = "2026-08-10"
        )

    @Test
    fun `渲染出图 宽度固定 高度与计算一致`() {
        val data = sampleData(
            listOf(
                StatsImageRenderer.RecordLine(true, "08-09 20:27~23:01 · 2小时33分 · sad"),
                StatsImageRenderer.RecordLine(false, "08-09 21:00 😄开心 · 看了场直播（原因：她唱了我点的歌）")
            )
        )
        val bmp = StatsImageRenderer.render(context, data)
        assertEquals(StatsImageRenderer.WIDTH, bmp.width)
        assertEquals(StatsImageRenderer.computeHeight(data), bmp.height)
        assertTrue("含记录时高度应超过固定段", bmp.height > 1350)
    }

    @Test
    fun `记录越多图越高`() {
        val few = sampleData(List(2) { StatsImageRenderer.RecordLine(true, "行 $it") })
        val many = sampleData(List(20) { StatsImageRenderer.RecordLine(true, "行 $it") })
        assertTrue(StatsImageRenderer.computeHeight(many) > StatsImageRenderer.computeHeight(few))
        // 渲染大列表不炸
        val bmp = StatsImageRenderer.render(context, many)
        assertEquals(StatsImageRenderer.computeHeight(many), bmp.height)
    }

    @Test
    fun `空记录空心算心情也能出图`() {
        val data = sampleData(emptyList()).copy(moodStats = emptyList())
        val bmp = StatsImageRenderer.render(context, data)
        assertEquals(StatsImageRenderer.WIDTH, bmp.width)
        assertEquals(StatsImageRenderer.computeHeight(data), bmp.height)
    }
}
