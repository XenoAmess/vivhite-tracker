package com.bilibili.livemonitor.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 长宣传图渲染的纯逻辑部分（QR 位图与整图尺寸）。
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
    fun `二维码含黑白模块即内容非空`() {
        val qr = PromoImageRenderer.renderQr(100)
        var black = 0
        var white = 0
        for (x in 0 until 100) for (y in 0 until 100) {
            if (qr.getPixel(x, y) == Color.BLACK) black++ else white++
        }
        assertTrue("二维码必须有黑色模块", black > 500)
        assertTrue("二维码必须有白色模块", white > 500)
    }

    @Test
    fun `长图尺寸为1080x1680 无封面用占位块也能渲染`() {
        val promo = PromoImageRenderer.render(null, "白绮还没开播", "白绮还没开播，先来直播间蹲一个开播！")
        assertEquals(PromoImageRenderer.WIDTH, promo.width)
        assertEquals(PromoImageRenderer.HEIGHT, promo.height)
    }
}
