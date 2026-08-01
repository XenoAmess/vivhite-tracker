package com.bilibili.livemonitor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 长宣传图渲染：封面 + 状态文案 + 直播间二维码 + 落款。
 *
 * 设计动机（2026-08 分享改造预研结论）：QQ/微信接收 ACTION_SEND 图片时
 * 会丢弃 EXTRA_TEXT，图文在它们手里只剩图——把文案和二维码烙进图里，
 * 任何分享目标都不可能丢信息。
 *
 * [renderQr] 是 ZXing 纯 JVM 路径，可单测；整图绘制走 instrumented 验证。
 */
object PromoImageRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1680
    const val QR_SIZE = 360

    /** 二维码内容：净 URL，任何扫码工具都能开（不带归因参数，码更稀疏易扫） */
    const val QR_CONTENT = "https://live.bilibili.com/11258892"

    /** 直播间二维码位图（纯 ZXing，无 Canvas 依赖，单测可断言） */
    fun renderQr(size: Int = QR_SIZE): Bitmap {
        val matrix = QRCodeWriter().encode(QR_CONTENT, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /**
     * 渲染长宣传图（1080×1680 竖版）。
     * 自上而下：封面 → 状态主标题 → 状态文案 → 二维码 → 扫码提示 → 落款。
     *
     * @param cover 直播间封面（null 用占位色块）
     * @param headline 状态主标题（如 "白绮开播啦！「失眠 无言」" / "白绮还没开播"）
     * @param body 状态感知文案（见 ShareTextDecider.body）
     */
    fun render(cover: Bitmap?, headline: String, body: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // 封面：拉伸填充顶部 1080×608 区域（直播间封面本就是横版）
        val coverHeight = 608
        if (cover != null) {
            val dst = android.graphics.Rect(0, 0, WIDTH, coverHeight)
            canvas.drawBitmap(cover, null, dst, null)
        } else {
            val paint = Paint().apply { color = 0xFF6750A4.toInt() }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), coverHeight.toFloat(), paint)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1B1B1F.toInt()
            textSize = 56f
            isFakeBoldText = true
        }
        var y = coverHeight + 96f
        canvas.drawText(headline.take(24), 64f, y, textPaint)

        textPaint.apply {
            textSize = 38f
            isFakeBoldText = false
            color = 0xFF44464F.toInt()
        }
        y += 88f
        canvas.drawText(body.take(40), 64f, y, textPaint)

        // 二维码居中
        val qr = renderQr()
        val qrLeft = (WIDTH - QR_SIZE) / 2f
        val qrTop = 980f
        canvas.drawBitmap(qr, qrLeft, qrTop, null)

        textPaint.apply {
            textSize = 34f
            color = 0xFF1B1B1F.toInt()
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("扫码打开 B 站直播间", WIDTH / 2f, qrTop + QR_SIZE + 72f, textPaint)

        textPaint.apply {
            textSize = 28f
            color = 0xFF77777F.toInt()
        }
        canvas.drawText("来自「牢白播了吗」· 白绮开播监控", WIDTH / 2f, HEIGHT - 72f, textPaint)

        return bitmap
    }
}
