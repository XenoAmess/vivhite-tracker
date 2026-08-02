package com.bilibili.livemonitor.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 魔法期分享图（双主题立绘版）：angry 囚笼主题 / happy 庆祝主题，
 * 立绘以 36dp 圆角卡片嵌入。
 */
@RunWith(RobolectricTestRunner::class)
class MagicImageRendererTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `渲染尺寸为1080x1350海报`() {
        val bmp = MagicImageRenderer.render(context, true, "2026-07-31 00:00  ~  2026-08-03 00:00")
        assertEquals(MagicImageRenderer.WIDTH, bmp.width)
        assertEquals(MagicImageRenderer.HEIGHT, bmp.height)
    }

    @Test
    fun `双变体都非单色废图且立绘已嵌入`() {
        for (ongoing in listOf(true, false)) {
            val bmp = MagicImageRenderer.render(context, ongoing, "2026-07-31 00:00  ~  2026-08-03 00:00")
            val first = bmp.getPixel(0, 0)
            var differs = false
            var y = 0
            while (y < bmp.height && !differs) {
                var x = 0
                while (x < bmp.width && !differs) {
                    if (bmp.getPixel(x, y) != first) differs = true
                    x += 37
                }
                y += 37
            }
            assertTrue("ongoing=$ongoing 渲染结果必须有视觉内容", differs)

            // 立绘卡区域（居中 860²）必须存在与背景不同的像素（证明立绘嵌入而非纯色）
            val artCx = bmp.width / 2
            val artCy = 60 + 430
            val corner = bmp.getPixel(artCx - 420, artCy)
            val center = bmp.getPixel(artCx, artCy)
            assertTrue(
                "ongoing=$ongoing 立绘卡内必须有内容差异",
                corner != center || bmp.getPixel(artCx, artCy - 200) != center
            )
        }
    }
}
