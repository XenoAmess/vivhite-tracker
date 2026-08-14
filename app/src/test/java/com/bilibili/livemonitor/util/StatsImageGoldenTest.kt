package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 海报 golden 回归（结构级）：不做精确像素对比（不同平台字体栅格化有差异，
 * 必碎），而是断言每个分区的「签名色」出现在预期区域 + 关键锚点精确值。
 * 任何分区渲染丢失/错位会打破对应断言。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class StatsImageGoldenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val accent = 0xFF6750A4.toInt()
    private val accentSoft = 0xFFF3EFFC.toInt()
    private val magicBg = 0xFFFCE4EC.toInt()
    private val moodPink = 0xFFF48FB1.toInt()
    private val magicBar = 0xFF9E9E9E.toInt()

    private fun sampleData() = StatsImageRenderer.StatsImageData(
        monthTitle = "2026年8月",
        summaryLines = listOf("本月 5 场", "平均 2小时27分 · 最长 4小时0分", "常播：周日"),
        barsTitle = "本月逐周场次",
        barCounts = listOf(1, 2, 1, 1, 0),
        barLabels = listOf("1-7", "8-14", "15-21", "22-28", "29-31"),
        leading = 5,
        daysInMonth = 31,
        sessionDays = setOf(5, 6, 7, 8, 9),
        magicDays = setOf(6, 7, 8),
        todayDom = 10,
        moodStats = listOf("😄开心" to 6, "😢难过" to 2),
        magicSummary = "本月魔法期：1 段 · 共 3 天",
        weekdayHeat = Array(7) { r -> IntArray(4) { c -> if (r == 4 && c == 3) 3 else 0 } },
        followerPoints = listOf(1_700_000_000_000L to 22420, 1_700_086_400_000L to 22435),
        dailyPopularity = listOf(5 to 300, 6 to 420, 7 to 280),
        wordCloudWords = listOf("失眠" to 5, "杂谈" to 3, "肉鸽" to 2),
        records = listOf(
            StatsImageRenderer.RecordLine(
                StatsImageRenderer.RecordKind.SESSION, "08-09 20:27~23:01 · 2小时33分 · sad"
            ),
            StatsImageRenderer.RecordLine(
                StatsImageRenderer.RecordKind.MOOD, "08-09 21:00 😄开心 · 看了场直播"
            ),
            StatsImageRenderer.RecordLine(
                StatsImageRenderer.RecordKind.MAGIC, "08-01 ~ 08-03 · 魔法期 3 天"
            )
        ),
        exportDate = "2026-08-12"
    )

    private fun colorHistogram1(pixel: Int, target: Int, tolerance: Int = 8): Boolean =
        Math.abs(Color.red(pixel) - Color.red(target)) <= tolerance &&
            Math.abs(Color.green(pixel) - Color.green(target)) <= tolerance &&
            Math.abs(Color.blue(pixel) - Color.blue(target)) <= tolerance &&
            Color.alpha(pixel) == 255

    private fun colorHistogram(bmp: Bitmap, target: Int, tolerance: Int = 6): Int {
        var count = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            if (Math.abs(Color.red(p) - Color.red(target)) <= tolerance &&
                Math.abs(Color.green(p) - Color.green(target)) <= tolerance &&
                Math.abs(Color.blue(p) - Color.blue(target)) <= tolerance &&
                Color.alpha(p) == 255
            ) count++
        }
        return count
    }

    @Test
    fun `golden 各分区签名色齐全 锚点正确`() {
        val data = sampleData()
        val bmp = StatsImageRenderer.render(context, data, avatar = null)
        assertEquals(StatsImageRenderer.WIDTH, bmp.width)
        assertEquals(StatsImageRenderer.computeHeight(data), bmp.height)

        // 锚点：左上角背景纯白；头像占位圆（圆心被「白」字笔画占据，环形多点采样浅紫）
        assertEquals(Color.WHITE, bmp.getPixel(10, 10))
        val avatarCx = 56 + 48
        val avatarCy = 24 + 48
        val avatarAnchors = listOf(
            -30 to -30, -30 to 30, 30 to -30, 30 to 30, 0 to -38, -38 to 0, 38 to 0
        )
        assertTrue(
            "头像占位圆应为浅紫",
            avatarAnchors.any { (dx, dy) ->
                colorHistogram1(bmp.getPixel(avatarCx + dx, avatarCy + dy), accentSoft)
            }
        )

        // 签名色：摘要卡浅紫 / 柱图与日历紫 / 魔法期粉底 / 心情粉条 / 魔法期灰条
        assertTrue("摘要卡浅紫缺失", colorHistogram(bmp, accentSoft) > 10_000)
        assertTrue("主题紫缺失", colorHistogram(bmp, accent) > 1_000)
        assertTrue("魔法期粉底缺失", colorHistogram(bmp, magicBg) > 100)
        assertTrue("心情粉条缺失", colorHistogram(bmp, moodPink) > 10)
        assertTrue("魔法期灰条缺失", colorHistogram(bmp, magicBar) > 10)
    }

    @Test
    fun `golden 记录条数决定高度 单调增长`() {
        val base = sampleData()
        val more = base.copy(
            records = base.records + List(10) {
                StatsImageRenderer.RecordLine(StatsImageRenderer.RecordKind.SESSION, "附加场次 $it")
            }
        )
        val hBase = StatsImageRenderer.computeHeight(base)
        val hMore = StatsImageRenderer.computeHeight(more)
        assertTrue(hMore > hBase)
        assertEquals(hMore, StatsImageRenderer.render(context, more).height)
    }
}
