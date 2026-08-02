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

    // ---------- 美术统一修（2026-08）：QR 大白卡 → 贴码小白块 ----------

    private fun whiteRatio(promo: Bitmap, y0: Int, y1: Int): Float {
        var white = 0
        var total = 0
        for (y in y0 until y1) for (x in 0 until promo.width step 4) {
            val p = promo.getPixel(x, y)
            if (Color.red(p) > 235 && Color.green(p) > 235 && Color.blue(p) > 235) white++
            total++
        }
        return white.toFloat() / total
    }

    @Test
    fun `深色风格 QR 下方不得出现大白块`() {
        // 旧版 QR 卡 = 白底大卡 + 卡内 caption，QR 下方一整条白带（主诉丑点）。
        // 重构后白块紧贴 QR（静区 24px），QR 下方 60px 条带白色占比必须 <40%
        for (style in listOf(
            PromoImageRenderer.Style.DARK,
            PromoImageRenderer.Style.BLACK_SQUARE,
            PromoImageRenderer.Style.VINYL_RECORD,
            PromoImageRenderer.Style.CYBERPUNK_TERMINAL,
            PromoImageRenderer.Style.DASHBOARD,
            PromoImageRenderer.Style.HALLOWEEN,
            PromoImageRenderer.Style.NEW_YEAR_COUNTDOWN,
            PromoImageRenderer.Style.LENS_FLARE
        )) {
            val promo = PromoImageRenderer.render(style, null, "白绮还没开播", "白绮还没开播，先来直播间蹲一个开播！")
            val ratio = whiteRatio(promo, promo.height - 160, promo.height - 100)
            assertTrue("$style QR 下方白色占比 $ratio 应 <0.40", ratio < 0.40f)
        }
    }

    @Test
    fun `深色风格 QR 静区必须是白底`() {
        // 扫码可靠性：深色风格曾传深色 cardColor 导致 QR 黑模块压深底扫不出。
        // 统一白块后，QR 区中心行必须存在纯白静区像素
        for (style in listOf(
            PromoImageRenderer.Style.DARK,
            PromoImageRenderer.Style.BLACK_SQUARE,
            PromoImageRenderer.Style.CYBERPUNK_TERMINAL
        )) {
            val promo = PromoImageRenderer.render(style, null, "白绮还没开播", "白绮还没开播，先来直播间蹲一个开播！")
            var foundWhite = false
            val midY = promo.height * 3 / 4
            for (x in promo.width / 4 until promo.width * 3 / 4 step 4) {
                val p = promo.getPixel(x, midY)
                if (Color.red(p) > 245 && Color.green(p) > 245 && Color.blue(p) > 245) {
                    foundWhite = true; break
                }
            }
            assertTrue("$style QR 区必须存在白色静区", foundWhite)
        }
    }

    @Test
    fun `centerCropRect 保比例放大且居中裁边`() {
        // Robolectric 的 Canvas 对 shader/clipPath/drawBitmap 是 no-op，像素级测不了，
        // 改为验证裁剪矩形的数学性质（防拉伸的核心）
        val dst = android.graphics.RectF(0f, 0f, 952f, 512f) // LIGHT_CARD 封面区，约 1.86:1
        // dst 宽高比(1.86) > 封面(1.78)：按宽缩放、高度溢出居中裁
        val r = PromoImageRenderer.centerCropRect(1600, 900, dst)
        val ratio = r.width() / r.height()
        assertEquals("宽高比必须保持 16:9", 1600f / 900f, ratio, 0.001f)
        assertEquals("宽度必须填满", dst.width(), r.width(), 0.001f)
        assertTrue("高度必须 >= 目标高（裁上下）", r.height() >= dst.height())
        assertEquals("水平居中", dst.centerX(), r.centerX(), 0.001f)
        assertEquals("垂直居中", dst.centerY(), r.centerY(), 0.001f)

        // 反向：竖长封面进宽扁框：宽度填满、高度裁
        val r2 = PromoImageRenderer.centerCropRect(900, 1600, dst)
        assertEquals("宽高比保持", 900f / 1600f, r2.width() / r2.height(), 0.001f)
        assertEquals("宽度必须填满", dst.width(), r2.width(), 0.001f)
    }
}
