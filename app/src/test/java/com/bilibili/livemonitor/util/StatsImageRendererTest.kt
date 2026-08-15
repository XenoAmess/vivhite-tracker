package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
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
            summaryLines = listOf("本月 5 场", "平均 2小时27分 · 最长 4小时0分", "常播：周日"),
            barsTitle = "本月逐周场次",
            barCounts = listOf(1, 2, 1, 1, 0),
            barLabels = listOf("1-7", "8-14", "15-21", "22-28", "29-31"),
            leading = 5, // 2026-08-01 周六 → 周日=0 起 6 格
            daysInMonth = 31,
            sessionDays = setOf(5, 6, 7, 8, 9),
            magicDays = setOf(6, 7, 8),
            todayDom = 10,
            moodStats = listOf("😄开心" to 6, "😢难过" to 2),
            magicSummary = "本月魔法期：1 段 · 共 3 天",
            records = records,
            exportDate = "2026-08-10"
        )

    @Test
    fun `渲染出图 宽度固定 高度与计算一致`() {
        val data = sampleData(
            listOf(
                StatsImageRenderer.RecordLine(
                    StatsImageRenderer.RecordKind.SESSION,
                    "08-09 20:27~23:01 · 2小时33分 · sad"
                ),
                StatsImageRenderer.RecordLine(
                    StatsImageRenderer.RecordKind.MOOD,
                    "08-09 21:00 😄开心 · 看了场直播（原因：她唱了我点的歌）"
                ),
                StatsImageRenderer.RecordLine(
                    StatsImageRenderer.RecordKind.MAGIC,
                    "08-06 ~ 08-08 · 魔法期 3 天"
                )
            )
        )
        val bmp = StatsImageRenderer.render(context, data)
        assertEquals(StatsImageRenderer.WIDTH, bmp.width)
        assertEquals(StatsImageRenderer.computeHeight(context, data), bmp.height)
        assertTrue("含记录时高度应超过固定段", bmp.height > 1350)
    }

    @Test
    fun `带头像渲染不炸`() {
        val data = sampleData(emptyList())
        val avatar = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val bmp = StatsImageRenderer.render(context, data, avatar)
        assertEquals(StatsImageRenderer.WIDTH, bmp.width)
    }

    @Test
    fun `记录越多图越高`() {
        val few = sampleData(List(2) {
            StatsImageRenderer.RecordLine(StatsImageRenderer.RecordKind.SESSION, "行 $it")
        })
        val many = sampleData(List(20) {
            StatsImageRenderer.RecordLine(StatsImageRenderer.RecordKind.SESSION, "行 $it")
        })
        assertTrue(StatsImageRenderer.computeHeight(context, many) > StatsImageRenderer.computeHeight(context, few))
        // 渲染大列表不炸
        val bmp = StatsImageRenderer.render(context, many)
        assertEquals(StatsImageRenderer.computeHeight(context, many), bmp.height)
    }

    @Test
    fun `空记录空心算心情空魔法期也能出图`() {
        val data = sampleData(emptyList()).copy(
            moodStats = emptyList(), magicSummary = null, magicDays = emptySet()
        )
        val bmp = StatsImageRenderer.render(context, data)
        assertEquals(StatsImageRenderer.WIDTH, bmp.width)
        assertEquals(StatsImageRenderer.computeHeight(context, data), bmp.height)
    }

    @Test
    fun `魔法期统计行参与高度计算`() {
        val withMagic = sampleData(emptyList())
        val withoutMagic = sampleData(emptyList()).copy(magicSummary = null, magicDays = emptySet())
        assertTrue(
            StatsImageRenderer.computeHeight(context, withMagic) >
                StatsImageRenderer.computeHeight(context, withoutMagic)
        )
    }
}
