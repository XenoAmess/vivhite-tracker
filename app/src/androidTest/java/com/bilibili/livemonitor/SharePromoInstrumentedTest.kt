package com.bilibili.livemonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.util.PromoImageRenderer
import com.bilibili.livemonitor.util.ShareImageLoader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 宣传图渲染的真机/模拟器端到端验证（三风格，1080×1350）。
 *
 * 单测（Robolectric）只能证明 QR 矩阵、尺寸与非单色——Canvas 文字/封面合成、
 * PNG 编码、FileProvider 授权都依赖真 Android 图形栈，必须真机验证。
 */
@RunWith(AndroidJUnit4::class)
class SharePromoInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 三风格真机渲染落盘可读且FileProvider可授权() {
        val cover = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF6750A4.toInt())
        }
        val loader = ShareImageLoader()
        // 视觉走查模式：am instrument -e promoDump 1 时不删文件，adb pull 出 53 张人工看
        val dump = InstrumentationRegistry.getArguments().getString("promoDump") == "1"

        for (style in PromoImageRenderer.Style.values()) {
            val promo = PromoImageRenderer.render(
                style, cover,
                headline = "白绮开播啦！「失眠 无言」",
                body = "白绮正在直播 · 11258892 · 「失眠 无言」"
            )
            assertTrue("$style 宽度", promo.width == PromoImageRenderer.WIDTH)
            assertTrue("$style 高度", promo.height == PromoImageRenderer.HEIGHT)

            val file = loader.save(context, promo, "promo_test_${style.name}.png")
            promo.recycle()
            assertNotNull("$style 必须落盘", file)
            assertTrue("$style 不能是空文件（PNG 编码真实发生）", file!!.length() > 10_000)

            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            assertNotNull("$style 落盘 PNG 必须可解码", decoded)
            assertTrue(decoded!!.width == PromoImageRenderer.WIDTH)
            assertTrue(decoded.height == PromoImageRenderer.HEIGHT)

            val uri = loader.shareableUri(context, file)
            assertTrue(
                "必须经 FileProvider 授权: $uri",
                uri.toString().startsWith("content://com.bilibili.livemonitor.fileprovider/")
            )
            if (!dump) file.delete()
        }
    }

    @Test
    fun 浅色卡片风二维码区域真实含黑色模块() {
        // QR 贴码小白块（2026-08 美术统一修）：topY=880 起，QR 位图在块内 pad=24 处居中
        val promo = PromoImageRenderer.render(
            PromoImageRenderer.Style.LIGHT_CARD, null,
            headline = "白绮还没开播",
            body = "白绮还没开播，先来直播间蹲一个开播！"
        )
        val qrLeft = (PromoImageRenderer.WIDTH - PromoImageRenderer.QR_SIZE) / 2
        val qrTop = 880 + 24
        var hasBlack = false
        var x = qrLeft
        while (x < qrLeft + PromoImageRenderer.QR_SIZE && !hasBlack) {
            var y = qrTop
            while (y < qrTop + PromoImageRenderer.QR_SIZE && !hasBlack) {
                if (promo.getPixel(x, y) == android.graphics.Color.BLACK) hasBlack = true
                y += 4
            }
            x += 4
        }
        assertTrue("二维码区域必须有黑色模块", hasBlack)
        promo.recycle()
    }
}
