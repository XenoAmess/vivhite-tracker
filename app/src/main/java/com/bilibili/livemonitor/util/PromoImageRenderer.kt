package com.bilibili.livemonitor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 宣传图渲染（1080×1350，4:5 社交卡片比）：封面 + 状态文案 + 直播间二维码 + 落款。
 *
 * 设计动机（2026-08 分享改造预研结论）：QQ/微信接收 ACTION_SEND 图片时
 * 会丢弃 EXTRA_TEXT，图文在它们手里只剩图——把文案和二维码烙进图里，
 * 任何分享目标都不可能丢信息。
 *
 * 三种风格（用户在预览对话框里切换选择）：
 * - [Style.LIGHT_CARD]：浅紫渐变底 + 圆角封面卡 + 状态徽标 + 白 QR 卡
 * - [Style.BLUR_BG]：封面虚化铺满背景 + 中央悬浮白卡
 * - [Style.DARK]：深紫灰底 + 亮文字 + 白 QR 卡
 *
 * [renderQr] 是 ZXing 纯 JVM 路径，可单测；整图绘制走 instrumented 验证。
 */
object PromoImageRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1350
    const val QR_SIZE = 320

    /** 二维码内容：净 URL，任何扫码工具都能开（不带归因参数，码更稀疏易扫） */
    const val QR_CONTENT = "https://live.bilibili.com/11258892"

    enum class Style { LIGHT_CARD, BLUR_BG, DARK }

    fun render(style: Style, cover: Bitmap?, headline: String, body: String): Bitmap =
        when (style) {
            Style.LIGHT_CARD -> renderLightCard(cover, headline, body)
            Style.BLUR_BG -> renderBlurBg(cover, headline, body)
            Style.DARK -> renderDark(cover, headline, body)
        }

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
     * 图文分享的封面烙文案条：原图底部加半透明深色带 + 白色文案。
     * 聊天类应用收图片分享必丢 EXTRA_TEXT——烙进图里就永远丢不了。
     */
    fun renderCaptionedCover(cover: Bitmap, caption: String): Bitmap {
        val bandHeight = 96
        val bitmap = Bitmap.createBitmap(cover.width, cover.height + bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(cover, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
        canvas.drawRect(0f, cover.height.toFloat(), cover.width.toFloat(), bitmap.height.toFloat(), paint)
        paint.apply {
            color = Color.WHITE
            textSize = 34f
        }
        canvas.drawText(caption.take(32), 24f, cover.height + 62f, paint)
        return bitmap
    }

    // ==================== 风格 A：浅色卡片风 ====================

    private fun renderLightCard(cover: Bitmap?, headline: String, body: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 浅紫渐变底
        canvas.drawRect(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFFF3EEFA.toInt(), 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
            }
        )
        // 圆角封面卡（等比 center-crop，不拉伸变形）
        drawRoundedCover(canvas, Rect(64, 48, WIDTH - 64, 560), cover, 32f, 0xFF6750A4.toInt())
        val isLive = !headline.contains("还没开播")
        drawBadge(canvas, WIDTH / 2f, 628f, isLive, darkText = false)
        drawCenteredText(canvas, headline.take(24), WIDTH / 2f, 740f, 52f, 0xFF1B1B1F.toInt(), bold = true)
        drawCenteredText(canvas, body.take(40), WIDTH / 2f, 812f, 34f, 0xFF44464F.toInt())
        drawQrCard(canvas, WIDTH / 2f, 848f, cardColor = Color.WHITE, captionColor = 0xFF1B1B1F.toInt())
        drawCenteredText(canvas, "来自「牢白播了吗」· 白绮开播监控", WIDTH / 2f, HEIGHT - 36f, 26f, 0xFF77777F.toInt())
        return bitmap
    }

    // ==================== 风格 B：虚化背景 + 悬浮卡 ====================

    private fun renderBlurBg(cover: Bitmap?, headline: String, body: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 封面等比铺满后缩放模糊（低成本毛玻璃），无封面用品牌紫纯色
        if (cover != null) {
            val filled = centerCropTo(cover, WIDTH, HEIGHT)
            val tiny = Bitmap.createScaledBitmap(filled, WIDTH / 16, HEIGHT / 16, true)
            val blurred = Bitmap.createScaledBitmap(tiny, WIDTH, HEIGHT, true)
            canvas.drawBitmap(blurred, 0f, 0f, null)
            if (filled != cover) filled.recycle()
            tiny.recycle()
            blurred.recycle()
        } else {
            canvas.drawColor(0xFF6750A4.toInt())
        }
        // 40% 深色罩层，让白卡浮起来
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0x66000000 })

        // 中央悬浮白卡
        val card = RectF(84f, 190f, (WIDTH - 84).toFloat(), 1160f)
        canvas.drawRoundRect(card, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() })
        val isLive = !headline.contains("还没开播")
        drawBadge(canvas, WIDTH / 2f, 300f, isLive, darkText = false)
        drawCenteredText(canvas, headline.take(24), WIDTH / 2f, 430f, 50f, 0xFF1B1B1F.toInt(), bold = true)
        drawCenteredText(canvas, body.take(40), WIDTH / 2f, 506f, 32f, 0xFF44464F.toInt())
        drawQrCard(canvas, WIDTH / 2f, 556f, cardColor = 0xFFF6F3FA.toInt(), captionColor = 0xFF1B1B1F.toInt())
        // 卡外底部落款（压在虚化背景上）
        drawCenteredText(canvas, "来自「牢白播了吗」· 白绮开播监控", WIDTH / 2f, HEIGHT - 48f, 26f, 0xEEFFFFFF.toInt())
        return bitmap
    }

    // ==================== 风格 C：深色风 ====================

    private fun renderDark(cover: Bitmap?, headline: String, body: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFF241E2E.toInt(), 0xFF171320.toInt(), Shader.TileMode.CLAMP)
            }
        )
        drawRoundedCover(canvas, Rect(64, 48, WIDTH - 64, 560), cover, 32f, 0xFF4A4458.toInt())
        val isLive = !headline.contains("还没开播")
        drawBadge(canvas, WIDTH / 2f, 628f, isLive, darkText = false)
        drawCenteredText(canvas, headline.take(24), WIDTH / 2f, 740f, 52f, 0xFFF2EFF7.toInt(), bold = true)
        drawCenteredText(canvas, body.take(40), WIDTH / 2f, 812f, 34f, 0xFFC9C5D0.toInt())
        drawQrCard(canvas, WIDTH / 2f, 848f, cardColor = Color.WHITE, captionColor = 0xFF1B1B1F.toInt())
        drawCenteredText(canvas, "来自「牢白播了吗」· 白绮开播监控", WIDTH / 2f, HEIGHT - 36f, 26f, 0xFF8A8694.toInt())
        return bitmap
    }

    // ==================== 公共绘制件 ====================

    /** 圆角矩形封面：等比 center-crop 裁剪（不拉伸），null 用纯色占位 */
    private fun drawRoundedCover(canvas: Canvas, dst: Rect, cover: Bitmap?, radius: Float, placeholderColor: Int) {
        val path = Path().apply {
            addRoundRect(RectF(dst), radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        if (cover != null) {
            val src = centerCropSrc(cover.width, cover.height, dst.width(), dst.height())
            canvas.drawBitmap(cover, src, dst, null)
        } else {
            canvas.drawColor(placeholderColor)
        }
        canvas.restore()
    }

    /** 状态徽标药丸：彩色圆底 + 白字（🔴 直播中 / ⚪ 未开播） */
    private fun drawBadge(canvas: Canvas, centerX: Float, centerY: Float, isLive: Boolean, darkText: Boolean) {
        val text = if (isLive) "🔴 直播中" else "⚪ 未开播"
        val bgColor = if (isLive) 0xFFD32F2F.toInt() else 0xFF757575.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f }
        val textWidth = paint.measureText(text)
        val padH = 36f
        val padV = 18f
        val rect = RectF(
            centerX - textWidth / 2 - padH, centerY - 34f / 2 - padV,
            centerX + textWidth / 2 + padH, centerY + 34f / 2 + padV
        )
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint.apply { color = bgColor })
        paint.color = if (darkText) 0xFF1B1B1F.toInt() else Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, centerX, centerY + 12f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** 圆角白卡承载二维码 + 「扫码打开 B 站直播间」 */
    private fun drawQrCard(canvas: Canvas, centerX: Float, topY: Float, cardColor: Int, captionColor: Int) {
        val pad = 28f
        val captionH = 64f
        val cardW = QR_SIZE + pad * 2
        val cardH = QR_SIZE + pad * 2 + captionH
        val rect = RectF(centerX - cardW / 2, topY, centerX + cardW / 2, topY + cardH)
        canvas.drawRoundRect(rect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor })
        val qr = renderQr()
        canvas.drawBitmap(qr, centerX - QR_SIZE / 2, topY + pad, null)
        qr.recycle()
        drawCenteredText(canvas, "扫码打开 B 站直播间", centerX, topY + pad + QR_SIZE + 46f, 28f, captionColor)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, centerX: Float, baselineY: Float, size: Float, color: Int, bold: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            isFakeBoldText = bold
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, centerX, baselineY, paint)
    }

    /** 源图等比裁剪到目标宽高比的源矩形 */
    private fun centerCropSrc(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcAspect = srcW.toFloat() / srcH
        val dstAspect = dstW.toFloat() / dstH
        return if (srcAspect > dstAspect) {
            val w = (srcH * dstAspect).toInt()
            val left = (srcW - w) / 2
            Rect(left, 0, left + w, srcH)
        } else {
            val h = (srcW / dstAspect).toInt()
            val top = (srcH - h) / 2
            Rect(0, top, srcW, top + h)
        }
    }

    /** 源图等比裁剪缩放到目标尺寸（返回新 bitmap，可能等于源图） */
    private fun centerCropTo(cover: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val src = centerCropSrc(cover.width, cover.height, dstW, dstH)
        val cropped = Bitmap.createBitmap(cover, src.left, src.top, src.width(), src.height())
        return Bitmap.createScaledBitmap(cropped, dstW, dstH, true)
    }
}
