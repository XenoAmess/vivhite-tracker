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
 * 长宣传图渲染的真机/模拟器端到端验证。
 *
 * 单测（Robolectric）只能证明 QR 矩阵与尺寸——Canvas 文字/封面合成、
 * PNG 编码、FileProvider 授权都依赖真 Android 图形栈，必须真机验证。
 */
@RunWith(AndroidJUnit4::class)
class SharePromoInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 长宣传图真机渲染落盘可读且FileProvider可授权() {
        // 构造一张假封面（渐变块），走与生产完全相同的渲染+落盘路径
        val cover = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF6750A4.toInt())
        }
        val promo = PromoImageRenderer.render(
            cover,
            headline = "白绮开播啦！「失眠 无言」",
            body = "白绮正在直播 · 11258892 · 「失眠 无言」"
        )
        assertTrue(promo.width == PromoImageRenderer.WIDTH)
        assertTrue(promo.height == PromoImageRenderer.HEIGHT)

        val loader = ShareImageLoader()
        val file = loader.save(context, promo, "promo_test.png")
        promo.recycle()
        assertNotNull("长图必须落盘", file)
        assertTrue("长图不能是空文件（PNG 编码真实发生）", file!!.length() > 10_000)

        // 落盘的 PNG 必须可被解码且尺寸正确（证明渲染真实写入像素）
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull("落盘 PNG 必须可解码", decoded)
        assertTrue(decoded!!.width == PromoImageRenderer.WIDTH)
        assertTrue(decoded.height == PromoImageRenderer.HEIGHT)

        // FileProvider 授权 uri（ACTION_SEND EXTRA_STREAM 的可读性前提）
        val uri = loader.shareableUri(context, file)
        assertTrue(
            "必须经 FileProvider 授权: $uri",
            uri.toString().startsWith("content://com.bilibili.livemonitor.fileprovider/")
        )

        // 二维码区域必须含黑色模块（QR 真实合成进去了）。
        // 注意：QR 带静区白边，采样整片区域而不是角点
        val qrLeft = (PromoImageRenderer.WIDTH - PromoImageRenderer.QR_SIZE) / 2
        var hasBlack = false
        var x = qrLeft
        while (x < qrLeft + PromoImageRenderer.QR_SIZE && !hasBlack) {
            var y = 980
            while (y < 980 + PromoImageRenderer.QR_SIZE && !hasBlack) {
                if (decoded.getPixel(x, y) == android.graphics.Color.BLACK) hasBlack = true
                y += 4
            }
            x += 4
        }
        assertTrue("二维码区域必须有黑色模块", hasBlack)
        file.delete()
    }
}
