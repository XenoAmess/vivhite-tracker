package com.bilibili.livemonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 魔法期分享图：矮版式（800 高）+ 无直播间二维码（用户反馈 2026-08-02）。
 */
@RunWith(RobolectricTestRunner::class)
class MagicImageRendererTest {

    @Test
    fun `渲染尺寸为1080x800矮版式`() {
        val bmp = MagicImageRenderer.render(true, "2026-07-31 00:00  ~  2026-08-03 00:00")
        assertEquals(MagicImageRenderer.WIDTH, bmp.width)
        assertEquals(800, bmp.height)
        assertEquals(800, MagicImageRenderer.HEIGHT)
    }

    @Test
    fun `渲染结果非单色废图`() {
        val bmp = MagicImageRenderer.render(false, "还没有记录魔法期")
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
        assertTrue("渲染结果必须有视觉内容", differs)
    }
}
