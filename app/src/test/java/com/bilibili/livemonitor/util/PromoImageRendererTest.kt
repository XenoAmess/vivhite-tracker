package com.bilibili.livemonitor.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 宣传图渲染的纯逻辑部分（QR 位图、三风格整图、图文烙文案条）。
 * 真机解码可读性由 SharePromoInstrumentedTest 守护。
 */
@RunWith(RobolectricTestRunner::class)
class PromoImageRendererTest {

    @Test
    fun `二维码位图尺寸正确`() {
        val qr = PromoImageRenderer.renderQr(200)
        assertEquals(200, qr.width)
        assertEquals(200, qr.height)
    }

    @Test
    fun `二维码三个定位角区域存在黑色模块`() {
        // QR 的 finder pattern 在左上/右上/左下三个角落区域
        // （ZXing 输出带 4 模块静区，不能断言 (0,0) 像素，改扫区域）
        val qr = PromoImageRenderer.renderQr(PromoImageRenderer.QR_SIZE)
        val s = PromoImageRenderer.QR_SIZE
        fun regionHasBlack(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (x in x0 until x1) for (y in y0 until y1) {
                if (qr.getPixel(x, y) == Color.BLACK) return true
            }
            return false
        }
        assertTrue("左上角定位区", regionHasBlack(0, 0, s / 4, s / 4))
        assertTrue("右上角定位区", regionHasBlack(s * 3 / 4, 0, s, s / 4))
        assertTrue("左下角定位区", regionHasBlack(0, s * 3 / 4, s / 4, s))
    }

    @Test
    fun `三种风格都能渲染且尺寸为1080x1350`() {
        // 美术重做（2026-08）：旧 1080x1680 太长，改 4:5 社交卡片比
        for (style in PromoImageRenderer.Style.values()) {
            val promo = PromoImageRenderer.render(style, null, "白绮还没开播", "白绮还没开播，先来直播间蹲一个开播！")
            assertEquals("$style 宽度", PromoImageRenderer.WIDTH, promo.width)
            assertEquals("$style 高度", PromoImageRenderer.HEIGHT, promo.height)
            assertEquals("宣传图比例应为 4:5", 1350, promo.height)
        }
    }

    @Test
    fun `三种风格都不是单色废图`() {
        // 渲染结果必须有视觉内容（防布局全画到画布外的回归）
        for (style in PromoImageRenderer.Style.values()) {
            val promo = PromoImageRenderer.render(style, null, "白绮还没开播", "白绮还没开播，先来直播间蹲一个开播！")
            val first = promo.getPixel(0, 0)
            var differs = false
            var y = 0
            while (y < promo.height && !differs) {
                var x = 0
                while (x < promo.width && !differs) {
                    if (promo.getPixel(x, y) != first) differs = true
                    x += 53
                }
                y += 53
            }
            assertTrue("$style 渲染结果不能是单色废图", differs)
        }
    }

    @Test
    fun `图文烙文案条 底部加半透明深色带`() {
        // 图文修复核心：聊天类应用必丢 EXTRA_TEXT，文案烙进封面底部
        val cover = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF6750A4.toInt())
        }
        val captioned = PromoImageRenderer.renderCaptionedCover(cover, "白绮还没开播，先来直播间蹲一个开播！")

        assertEquals("宽度不变", 400, captioned.width)
        assertEquals("高度 = 原图 + 条带", 200 + 96, captioned.height)
        // 底部条带区域必须是深色（半透明黑压在图上）
        val bandPixel = captioned.getPixel(200, 200 + 48)
        assertTrue(
            "底部必须是深色条带: ${Integer.toHexString(bandPixel)}",
            Color.red(bandPixel) < 120 && Color.green(bandPixel) < 120 && Color.blue(bandPixel) < 120
        )
    }
}
