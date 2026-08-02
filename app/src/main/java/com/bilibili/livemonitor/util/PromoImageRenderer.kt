package com.bilibili.livemonitor.util

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.cos
import kotlin.math.sin

/**
 * 宣传图渲染（1080×1350，4:5 社交卡片比）：53 种风格（原 3 + 新 50），
 * 覆盖杂志/极简/中国风/复古/现代/游戏/信息图/节日/艺术抽象 9 大族。
 * 预览对话框用 3 列滚动 chip 列表供用户切换。
 */
object PromoImageRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1350
    const val QR_SIZE = 320
    const val QR_CONTENT = "https://live.bilibili.com/11258892"

    /** 53 种风格枚举。顺序 = strings.xml promo_style_names 顺序。 */
    enum class Style {
        // 原 3 风格（兼容旧 prefs 默认值）
        LIGHT_CARD, BLUR_BG, DARK,
        // F1 杂志/编辑
        MAGAZINE_COVER, NEWSPAPER_FRONT, TYPEWRITER, ACADEMIC_JOURNAL,
        EDITORIAL_PHOTO, OPED_COLUMN, BUSINESS_WEEKLY,
        // F2 极简/实验
        HUGE_TYPE, BLACK_SQUARE, SYSTEM_UI_MOCKUP, RECEIPT,
        LINE_ART_MINIMAL, SINGLE_GLYPH_POSTER,
        // F3 中国风
        INK_SCROLL, SONG_GREEN_LANDSCAPE, PAPER_CUT_WINDOW, SEAL_FOLD_PAGE, FAN,
        // F4 复古
        VINYL_RECORD, POLAROID, VHS_LABEL, VINTAGE_TV, CLASSIC_FILM_POSTER,
        // F5 现代/渐变
        GLASSMORPHISM, AURORA_GRADIENT, Y2K, NEO_BRUTALISM, THREE_D_FLOATER, GRADIENT_MESH,
        // F6 游戏/数字
        GAMING_HUD, PIXEL_ART, CYBERPUNK_TERMINAL, QR_DOMINANT, APP_STORE_LISTING, RETRO_WEB_2000,
        // F7 信息图
        DASHBOARD, EVENT_TICKET, WEATHER_CARD, NFT_CARD, BOARDING_PASS,
        // F8 节日
        BIRTHDAY_CAKE, HALLOWEEN, CHRISTMAS, VALENTINE, NEW_YEAR_COUNTDOWN,
        // F9 艺术抽象
        WATERCOLOR, HAND_DRAWN_DOODLE, LENS_FLARE, BIO_CELL, BLACK_WHITE_FILM;
    }

    fun chipColorOf(style: Style): Int {
        return when (style) {
            Style.LIGHT_CARD -> 0xFFF3EEFA.toInt()
            Style.BLUR_BG -> 0xFF6750A4.toInt()
            Style.DARK -> 0xFF241E2E.toInt()
            Style.MAGAZINE_COVER -> 0xFFE53935.toInt()
            Style.NEWSPAPER_FRONT -> 0xFFECEFF1.toInt()
            Style.TYPEWRITER -> 0xFFFFF8E1.toInt()
            Style.ACADEMIC_JOURNAL -> 0xFFFFFDE7.toInt()
            Style.EDITORIAL_PHOTO -> 0xFF263238.toInt()
            Style.OPED_COLUMN -> 0xFFFFEBEE.toInt()
            Style.BUSINESS_WEEKLY -> 0xFFFFEB3B.toInt()
            Style.HUGE_TYPE -> 0xFFFFFFFF.toInt()
            Style.BLACK_SQUARE -> 0xFF000000.toInt()
            Style.SYSTEM_UI_MOCKUP -> 0xFFECEFF1.toInt()
            Style.RECEIPT -> 0xFFFFFFFF.toInt()
            Style.LINE_ART_MINIMAL -> 0xFFFAF5E6.toInt()
            Style.SINGLE_GLYPH_POSTER -> 0xFFFF6F61.toInt()
            Style.INK_SCROLL -> 0xFFF7F1E1.toInt()
            Style.SONG_GREEN_LANDSCAPE -> 0xFFB5C9A2.toInt()
            Style.PAPER_CUT_WINDOW -> 0xFFD32F2F.toInt()
            Style.SEAL_FOLD_PAGE -> 0xFFE8DCC4.toInt()
            Style.FAN -> 0xFFFFE0B2.toInt()
            Style.VINYL_RECORD -> 0xFF212121.toInt()
            Style.POLAROID -> 0xFFFFFDF6.toInt()
            Style.VHS_LABEL -> 0xFFE0E0E0.toInt()
            Style.VINTAGE_TV -> 0xFF424242.toInt()
            Style.CLASSIC_FILM_POSTER -> 0xFFF5E6C2.toInt()
            Style.GLASSMORPHISM -> 0xFF1A237E.toInt()
            Style.AURORA_GRADIENT -> 0xFF6A1B9A.toInt()
            Style.Y2K -> 0xFFEC407A.toInt()
            Style.NEO_BRUTALISM -> 0xFFFDD835.toInt()
            Style.THREE_D_FLOATER -> 0xFF7E57C2.toInt()
            Style.GRADIENT_MESH -> 0xFFFF6E40.toInt()
            Style.GAMING_HUD -> 0xFF00E676.toInt()
            Style.PIXEL_ART -> 0xFF6D4C41.toInt()
            Style.CYBERPUNK_TERMINAL -> 0xFF000000.toInt()
            Style.QR_DOMINANT -> 0xFFFAFAFA.toInt()
            Style.APP_STORE_LISTING -> 0xFFFFFFFF.toInt()
            Style.RETRO_WEB_2000 -> 0xFFB3E5FC.toInt()
            Style.DASHBOARD -> 0xFF0D1B2A.toInt()
            Style.EVENT_TICKET -> 0xFFFFE082.toInt()
            Style.WEATHER_CARD -> 0xFF90CAF9.toInt()
            Style.NFT_CARD -> 0xFF311B92.toInt()
            Style.BOARDING_PASS -> 0xFFFFF8E7.toInt()
            Style.BIRTHDAY_CAKE -> 0xFFF8BBD0.toInt()
            Style.HALLOWEEN -> 0xFF4A148C.toInt()
            Style.CHRISTMAS -> 0xFFB71C1C.toInt()
            Style.VALENTINE -> 0xFFF48FB1.toInt()
            Style.NEW_YEAR_COUNTDOWN -> 0xFF0D1B4A.toInt()
            Style.WATERCOLOR -> 0xFFFFE0E6.toInt()
            Style.HAND_DRAWN_DOODLE -> 0xFFFFFDF5.toInt()
            Style.LENS_FLARE -> 0xFF1A1A1A.toInt()
            Style.BIO_CELL -> 0xFFB2DFDB.toInt()
            Style.BLACK_WHITE_FILM -> 0xFF424242.toInt()
        }
    }


    fun render(style: Style, cover: Bitmap?, headline: String, body: String): Bitmap =
        when (style) {
            Style.LIGHT_CARD -> renderLightCard(cover, headline, body)
            Style.BLUR_BG -> renderBlurBg(cover, headline, body)
            Style.DARK -> renderDark(cover, headline, body)
            Style.MAGAZINE_COVER -> renderMagazineCover(cover, headline, body)
            Style.NEWSPAPER_FRONT -> renderNewspaperFront(cover, headline, body)
            Style.TYPEWRITER -> renderTypewriter(cover, headline, body)
            Style.ACADEMIC_JOURNAL -> renderAcademicJournal(cover, headline, body)
            Style.EDITORIAL_PHOTO -> renderEditorialPhoto(cover, headline, body)
            Style.OPED_COLUMN -> renderOpedColumn(cover, headline, body)
            Style.BUSINESS_WEEKLY -> renderBusinessWeekly(cover, headline, body)
            Style.HUGE_TYPE -> renderHugeType(cover, headline, body)
            Style.BLACK_SQUARE -> renderBlackSquare(cover, headline, body)
            Style.SYSTEM_UI_MOCKUP -> renderSystemUiMockup(cover, headline, body)
            Style.RECEIPT -> renderReceipt(cover, headline, body)
            Style.LINE_ART_MINIMAL -> renderLineArtMinimal(cover, headline, body)
            Style.SINGLE_GLYPH_POSTER -> renderSingleGlyph(cover, headline, body)
            Style.INK_SCROLL -> renderInkScroll(cover, headline, body)
            Style.SONG_GREEN_LANDSCAPE -> renderSongGreen(cover, headline, body)
            Style.PAPER_CUT_WINDOW -> renderPaperCut(cover, headline, body)
            Style.SEAL_FOLD_PAGE -> renderSealFold(cover, headline, body)
            Style.FAN -> renderFan(cover, headline, body)
            Style.VINYL_RECORD -> renderVinyl(cover, headline, body)
            Style.POLAROID -> renderPolaroid(cover, headline, body)
            Style.VHS_LABEL -> renderVhs(cover, headline, body)
            Style.VINTAGE_TV -> renderVintageTv(cover, headline, body)
            Style.CLASSIC_FILM_POSTER -> renderClassicFilm(cover, headline, body)
            Style.GLASSMORPHISM -> renderGlass(cover, headline, body)
            Style.AURORA_GRADIENT -> renderAurora(cover, headline, body)
            Style.Y2K -> renderY2k(cover, headline, body)
            Style.NEO_BRUTALISM -> renderNeoBrutal(cover, headline, body)
            Style.THREE_D_FLOATER -> renderThreeD(cover, headline, body)
            Style.GRADIENT_MESH -> renderMesh(cover, headline, body)
            Style.GAMING_HUD -> renderHud(cover, headline, body)
            Style.PIXEL_ART -> renderPixel(cover, headline, body)
            Style.CYBERPUNK_TERMINAL -> renderCyber(cover, headline, body)
            Style.QR_DOMINANT -> renderQrDominant(cover, headline, body)
            Style.APP_STORE_LISTING -> renderAppStore(cover, headline, body)
            Style.RETRO_WEB_2000 -> renderRetroWeb(cover, headline, body)
            Style.DASHBOARD -> renderDashboard(cover, headline, body)
            Style.EVENT_TICKET -> renderEventTicket(cover, headline, body)
            Style.WEATHER_CARD -> renderWeather(cover, headline, body)
            Style.NFT_CARD -> renderNft(cover, headline, body)
            Style.BOARDING_PASS -> renderBoarding(cover, headline, body)
            Style.BIRTHDAY_CAKE -> renderBirthday(cover, headline, body)
            Style.HALLOWEEN -> renderHalloween(cover, headline, body)
            Style.CHRISTMAS -> renderChristmas(cover, headline, body)
            Style.VALENTINE -> renderValentine(cover, headline, body)
            Style.NEW_YEAR_COUNTDOWN -> renderNewYear(cover, headline, body)
            Style.WATERCOLOR -> renderWatercolor(cover, headline, body)
            Style.HAND_DRAWN_DOODLE -> renderDoodle(cover, headline, body)
            Style.LENS_FLARE -> renderLensFlare(cover, headline, body)
            Style.BIO_CELL -> renderBioCell(cover, headline, body)
            Style.BLACK_WHITE_FILM -> renderBwFilm(cover, headline, body)
        }

    /** ZXing 纯 JVM QR 位图 */
    fun renderQr(size: Int = QR_SIZE): Bitmap {
        val matrix = QRCodeWriter().encode(QR_CONTENT, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }

    /** 图文分享的封面烙文案条 */
    fun renderCaptionedCover(cover: Bitmap, caption: String): Bitmap {
        val bandHeight = 96
        val bitmap = Bitmap.createBitmap(cover.width, cover.height + bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(cover, 0f, 0f, null)
        canvas.drawRect(0f, cover.height.toFloat(), cover.width.toFloat(), bitmap.height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() })
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
            .also { canvas.drawText(caption.take(32), 24f, cover.height + 62f, it) }
        return bitmap
    }

    // ==================== 公共绘制件 ====================

    internal fun newBitmap() = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

    internal fun paintText(
        size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        isFakeBoldText = bold
        textAlign = align
    }

    private fun Paint.setSerif(): Paint { typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); return this }
    private fun Paint.setMono(): Paint { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); return this }
    private fun Paint.setSerifBold(): Paint { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); return this }

    internal fun drawCenter(canvas: Canvas, paint: Paint, text: String, cx: Float, y: Float) {
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, cx, y, paint)
    }

    private fun drawTextLeft(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float) {
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, x, y, paint)
    }

    internal fun drawCenterClipped(canvas: Canvas, paint: Paint, text: String, cx: Float, baselineY: Float, maxWidth: Float) {
        var s = text
        while (paint.measureText(s) > maxWidth && s.length > 1) s = s.dropLast(1)
        if (s != text) s = s.dropLast(1) + "…"
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(s, cx, baselineY, paint)
    }

    private fun drawRoundedCover(canvas: Canvas, dst: Rect, cover: Bitmap?, radius: Float, placeholderColor: Int) {
        drawRoundedCover(canvas, RectF(dst), cover, radius, placeholderColor)
    }

    // centerCrop 目标矩形计算（纯函数，可单测宽高比保持）：保比例放大并居中裁边
    internal fun centerCropRect(coverW: Int, coverH: Int, dst: RectF): RectF {
        val scale = maxOf(dst.width() / coverW, dst.height() / coverH)
        val w = coverW * scale
        val h = coverH * scale
        val left = dst.left + (dst.width() - w) / 2f
        val top = dst.top + (dst.height() - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    private fun drawRoundedCover(canvas: Canvas, dst: RectF, cover: Bitmap?, radius: Float, placeholderColor: Int) {
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        if (cover != null) {
            // centerCrop：保比例放大裁边，防止封面被拉伸变形（B站封面 16:9，直接
            // drawBitmap(cover, null, dst) 会按 dst 比例硬拉，人脸变形）
            canvas.drawBitmap(cover, null, centerCropRect(cover.width, cover.height, dst), null)
        } else {
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = placeholderColor })
        }
        canvas.restore()
    }

    /**
     * 贴码小白块：QR + 静区（24px）即全部，无 caption 带。
     * 统一纯白底——静区是扫码可靠性要求；深色风格传深色卡的旧策略会让
     * QR 黑模块压深底扫不出，已废弃。浅色背景上 shadow=true 加柔和投影防"白上白消失"。
     */
    internal fun drawQrCard(
        canvas: Canvas, cx: Float, topY: Float,
        qrSize: Float = QR_SIZE.toFloat(), shadow: Boolean = false
    ) {
        val pad = 24f
        val cardW = qrSize + pad * 2
        val rect = RectF(cx - cardW / 2, topY, cx + cardW / 2, topY + cardW)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            if (shadow) setShadowLayer(16f, 0f, 6f, 0x33000000)
        }
        canvas.drawRoundRect(rect, 20f, 20f, cardPaint)
        val qr = renderQr(qrSize.toInt())
        canvas.drawBitmap(qr, cx - qrSize / 2, topY + pad, null)
        qr.recycle()
    }

    // 白块高度（供样式排版计算 QR 区底部边界）
    internal fun qrCardHeight(qrSize: Float = QR_SIZE.toFloat()): Float = qrSize + 48f

    private fun drawTextOnPathCenter(canvas: Canvas, paint: Paint, text: String, path: Path, baselineOffset: Float) {
        paint.textAlign = Paint.Align.CENTER
        canvas.drawTextOnPath(text, path, 0f, baselineOffset, paint)
    }

    internal fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alphaMax: Int) {
        val colors = intArrayOf(
            Color.argb(alphaMax, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cy, radius, colors, floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, p)
    }

    private fun Bitmap.toGrayscale(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        c.drawBitmap(this, 0f, 0f, p)
        return bmp
    }

    private fun Bitmap.toTinted(tintColor: Int, satMul: Float = 0.85f): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val m = ColorMatrix().apply { setSaturation(satMul) }
        m.postConcat(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, Color.red(tintColor).toFloat(),
            0f, 0f, 0f, 0f, Color.green(tintColor).toFloat(),
            0f, 0f, 0f, 0f, Color.blue(tintColor).toFloat(),
            0f, 0f, 0f, 1f, 0f
        )))
        p.colorFilter = ColorMatrixColorFilter(m)
        c.drawBitmap(this, 0f, 0f, p)
        return bmp
    }

    /** 直播状态徽标药丸：彩色圆底 + 白字（🔴 直播中 / ⚪ 未开播） */
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

    private fun drawLiveBadge(canvas: Canvas, cx: Float, cy: Float, isLive: Boolean) {
        drawBadge(canvas, cx, cy, isLive, darkText = false)
    }

    private fun drawHeart(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val p = Path()
        p.moveTo(cx, cy + size * 0.4f)
        p.cubicTo(cx, cy, cx - size, cy, cx - size, cy - size * 0.3f)
        p.cubicTo(cx - size, cy - size * 0.8f, cx - size * 0.4f, cy - size * 0.7f, cx, cy - size * 0.2f)
        p.cubicTo(cx + size * 0.4f, cy - size * 0.7f, cx + size, cy - size * 0.8f, cx + size, cy - size * 0.3f)
        p.cubicTo(cx + size, cy, cx, cy, cx, cy + size * 0.4f)
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun isLiveFromHeadline(headline: String): Boolean = !headline.contains("还没开播")

    private fun centerCropTo(cover: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val src = centerCropSrc(cover.width, cover.height, dstW, dstH)
        val cropped = Bitmap.createBitmap(cover, src.left, src.top, src.width(), src.height())
        return Bitmap.createScaledBitmap(cropped, dstW, dstH, true)
    }

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

    // ===F1===
    private fun renderMagazineCover(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F4EC.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), 220f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        c.drawText("VOL.11258892", 56f, 200f, paintText(46f, 0xFFEFE7DA.toInt()).setMono())
        drawRoundedCover(c, Rect(64, 260, WIDTH - 64, 260 + 620), cover, 12f, 0xFF6750A4.toInt())
        drawCenter(c, paintText(54f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 960f)
        drawCenterClipped(c, paintText(30f, 0xFF555555.toInt()).setSerif(), body, WIDTH / 2f, 1010f, 980f)
        drawQrCard(c, WIDTH / 2f, 1044f, qrSize = 220f, shadow = true)
        return b
    }

    private fun renderNewspaperFront(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFECEFF1.toInt() })
        c.drawRect(48f, 48f, WIDTH - 48f, 50f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        c.drawRect(48f, 56f, WIDTH - 48f, 58f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        drawTextLeft(c, paintText(64f, 0xFF1A1A1A.toInt(), bold = true).setSerif(), "牢 白 日 报", 56f, 180f)
        drawTextLeft(c, paintText(22f, 0xFF757575.toInt()).setMono(), "第 11258 期   2026", 56f, 214f)
        c.drawLine(48f, 246f, WIDTH - 48f, 246f, Paint().apply { color = 0xFFBDBDBD.toInt(); strokeWidth = 1f })
        drawRoundedCover(c, Rect(56, 280, 460, 680), cover, 4f, 0xFF424242.toInt())
        drawCenterClipped(c, paintText(38f, 0xFF1A1A1A.toInt(), bold = true).setSerif(), headline.take(24), WIDTH / 2f + 240f, 360f, 480f)
        drawCenterClipped(c, paintText(22f, 0xFF424242.toInt()).setSerif(), body, WIDTH / 2f + 240f, 410f, 480f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()).setSerif(), "——  记者 牢白  报道", WIDTH / 2f + 240f, 460f, 480f)
        drawQrCard(c, WIDTH / 2f, 740f, qrSize = 280f)
        return b
    }

    private fun renderTypewriter(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF8E1.toInt() })
        val gray = cover?.toGrayscale()
        drawRoundedCover(c, Rect(380, 200, 700, 520), gray, 4f, 0xFFBDBDBD.toInt())
        gray?.recycle()
        val mono = paintText(36f, 0xFF1A1A1A.toInt()).setMono()
        drawCenter(c, mono, "PRESS  RELEASE", WIDTH / 2f, 580f)
        c.drawLine(140f, 600f, WIDTH - 140f, 600f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 2f })
        drawCenter(c, paintText(32f, 0xFF1A1A1A.toInt()).setMono(), headline.take(30), WIDTH / 2f, 670f)
        drawCenterClipped(c, paintText(22f, 0xFF424242.toInt()).setMono(), body, WIDTH / 2f, 720f, 900f)
        drawCenterClipped(c, paintText(22f, 0xFF424242.toInt()).setMono(), "Released: 11258892", WIDTH / 2f, 760f, 900f)
        c.drawLine(140f, 1130f, WIDTH - 140f, 1130f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 1f })
        drawCenter(c, paintText(18f, 0xFF616161.toInt()).setMono(), "[ 来自「牢白播了吗」  ·  监控类应用  ·  v1.0 ]", WIDTH / 2f, 1170f)
        drawQrCard(c, WIDTH / 2f, 820f, qrSize = 240f)
        return b
    }

    private fun renderAcademicJournal(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFDE7.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), 4f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        c.drawRect(0f, HEIGHT - 4f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0xFF1A1A1A.toInt() })
        drawCenter(c, paintText(18f, 0xFF424242.toInt()).setSerif(), "Journal of Room Watching Studies  ·  Vol. 11258  ·  ISSN 1234-5678", WIDTH / 2f, 60f)
        c.drawLine(80f, 80f, WIDTH - 80f, 80f, Paint().apply { color = 0xFF757575.toInt(); strokeWidth = 1f })
        c.save(); c.rotate(-90f, 64f, HEIGHT / 2f)
        drawTextLeft(c, paintText(20f, 0xFF616161.toInt()).setSerif(), "BAI QI  WATCH  REPORT", 64f, HEIGHT / 2f - 8f)
        c.restore()
        drawRoundedCover(c, Rect(140, 140, WIDTH - 140, 580), cover, 0f, 0xFFB0B0B0.toInt())
        drawCenter(c, paintText(36f, 0xFF1A1A1A.toInt(), bold = true).setSerif(), headline.take(26), WIDTH / 2f, 660f)
        c.drawLine(WIDTH / 2f - 100, 686f, WIDTH / 2f + 100, 686f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 1.5f })
        drawCenterClipped(c, paintText(22f, 0xFF424242.toInt()).setSerif(), body, WIDTH / 2f, 750f, 920f)
        drawCenterClipped(c, paintText(20f, 0xFF616161.toInt()).setSerif(), "Abstract. — " + body.take(60), WIDTH / 2f, 790f, 920f)
        drawQrCard(c, WIDTH / 2f, 850f)
        return b
    }

    private fun renderEditorialPhoto(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1ECE0.toInt() })
        drawRoundedCover(c, Rect(0, 60, WIDTH, 760), cover, 0f, 0xFF424242.toInt())
        val overlay = Paint().apply {
            shader = LinearGradient(0f, 600f, 0f, 760f, 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 600f, WIDTH.toFloat(), 760f, overlay)
        drawCenterClipped(c, paintText(40f, 0xFFFFFFFF.toInt(), bold = true).setSerif(), headline.take(22), WIDTH / 2f, 720f, 900f)
        drawCenter(c, paintText(16f, 0xFFE0E0E0.toInt()).setSerif(), "——  摄影 / 现场  /  直播  /  目击  ", WIDTH / 2f, 750f)
        c.drawLine(80f, 820f, WIDTH - 80f, 820f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 1f })
        drawCenterClipped(c, paintText(22f, 0xFF333333.toInt()).setSerif(), body, WIDTH / 2f, 880f, 920f)
        drawCenterClipped(c, paintText(20f, 0xFF555555.toInt()).setSerif(), "—— 摄影·报道 ·  牢白", WIDTH / 2f, 930f, 920f)
        drawCenterClipped(c, paintText(16f, 0xFF888888.toInt()).setSerif(), "  white-room.live / 11258892  ", WIDTH / 2f, 970f, 920f)
        drawQrCard(c, WIDTH / 2f, 980f, qrSize = 280f, shadow = true)
        return b
    }

    private fun renderOpedColumn(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAFAFA.toInt() })
        c.drawRect(80f, 80f, 96f, HEIGHT - 80f, Paint().apply { color = 0xFFB71C1C.toInt() })
        drawTextLeft(c, paintText(16f, 0xFFB71C1C.toInt()), "OP-ED", 60f, 70f)
        drawRoundedCover(c, Rect(140, 100, 380, 280), cover, 4f, 0xFFBDBDBD.toInt())
        drawCenterClipped(c, paintText(30f, 0xFF1A1A1A.toInt(), bold = true).setSerif(), headline.take(22), WIDTH / 2f + 140f, 220f, 460f)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = 0xFF1A1A1A.toInt(); textAlign = Paint.Align.LEFT }
        var ty = 360f
        for (line in body.chunked(20)) {
            if (ty > 1020f) break
            c.drawText(line.take(20), 140f, ty, tp); ty += 34f
        }
        c.drawLine(140f, 1050f, WIDTH - 140f, 1050f, Paint().apply { color = 0xFFBDBDBD.toInt(); strokeWidth = 1f })
        drawTextLeft(c, paintText(16f, 0xFF888888.toInt()), "  ·  牢白  ·  ", 140f, 1080f)
        drawQrCard(c, WIDTH - 200f, 1042f, qrSize = 220f, shadow = true)
        return b
    }

    private fun renderBusinessWeekly(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFEB3B.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), 90f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        drawTextLeft(c, paintText(40f, 0xFFFFEB3B.toInt(), bold = true), "WATCH  WEEKLY", 60f, 60f)
        drawTextLeft(c, paintText(20f, 0xFFFFEB3B.toInt()).setMono(), "ISSUE 11258", WIDTH - 280f, 60f)
        c.drawRect(0f, 90f, WIDTH.toFloat(), 130f, Paint().apply { color = 0xFFE53935.toInt() })
        c.drawText("\u00a5 \u514d\u8d39", 80f, 122f, paintText(36f, 0xFFFFFFFF.toInt(), bold = true))
        drawRoundedCover(c, Rect(72, 168, WIDTH - 72, 168 + 580), cover, 8f, 0xFFD32F2F.toInt())
        drawCenter(c, paintText(48f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 840f)
        drawCenterClipped(c, paintText(24f, 0xFF424242.toInt()), body, WIDTH / 2f, 896f, 900f)
        c.drawLine(80f, 970f, WIDTH - 80f, 970f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 3f })
        drawQrCard(c, WIDTH / 2f, 940f, shadow = true)
        return b
    }
    // ===F2===
    private fun renderHugeType(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        val isLive = isLiveFromHeadline(headline)
        val glyph = if (isLive) "\u770b" else "\u7b49"
        drawCenter(c, paintText(560f, 0xFFE0E0E0.toInt()), glyph, WIDTH / 2f, 700f)
        drawCenter(c, paintText(54f, 0xFF1A1A1A.toInt()), headline.take(14), WIDTH / 2f, 880f)
        c.drawLine(WIDTH / 2f - 60, 920f, WIDTH / 2f + 60, 920f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 1.5f })
        drawCenterClipped(c, paintText(24f, 0xFF666666.toInt()), body, WIDTH / 2f, 970f, 900f)
        drawQrCard(c, WIDTH / 2f, 1020f, qrSize = 240f)
        return b
    }

    private fun renderBlackSquare(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        c.drawLine(80f, 200f, WIDTH - 80f, 200f, Paint().apply { color = 0xFFFAFAFA.toInt(); strokeWidth = 1f })
        c.drawRect(WIDTH / 2f - 60f, 480f, WIDTH / 2f + 60f, 600f, Paint().apply { color = 0xFFFAFAFA.toInt() })
        drawCenter(c, paintText(40f, 0xFF000000.toInt(), bold = true), "LQ", WIDTH / 2f, 562f)
        drawCenterClipped(c, paintText(32f, 0xFFFAFAFA.toInt()), headline.take(20), WIDTH / 2f, 720f, 900f)
        drawCenterClipped(c, paintText(20f, 0xFFBDBDBD.toInt()), body, WIDTH / 2f, 780f, 900f)
        c.drawLine(80f, HEIGHT - 200f, WIDTH - 80f, HEIGHT - 200f, Paint().apply { color = 0xFFFAFAFA.toInt(); strokeWidth = 1f })
        drawQrCard(c, WIDTH / 2f, HEIGHT - 470f, qrSize = 240f)
        return b
    }

    private fun renderSystemUiMockup(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F5.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), 90f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        drawTextLeft(c, paintText(20f, 0xFFFFFFFF.toInt()).setMono(), " 9:41   \u2022\u2022\u2022\u2022 100%", 60f, 60f)
        c.drawRoundRect(RectF(80f, 200f, (WIDTH - 80f).toFloat(), 540f), 24f, 24f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        drawTextLeft(c, paintText(16f, 0xFF888888.toInt()).setMono(), "\u767d\u7eda\u76f4\u64ad\u95f4 \u00b7 \u76f4\u64ad\u76d1\u63a7", 110f, 250f)
        drawCenterClipped(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true), headline.take(18), WIDTH / 2f, 300f, 880f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()), body, WIDTH / 2f, 360f, 880f)
        c.drawRoundRect(RectF(WIDTH / 2f - 140, 400f, WIDTH / 2f + 140, 460f), 24f, 24f, Paint().apply { color = 0xFF6750A4.toInt() })
        drawCenter(c, paintText(22f, 0xFFFFFFFF.toInt(), bold = true), "\u7acb\u5373\u6253\u5f00\u76f4\u64ad\u95f4", WIDTH / 2f, 440f)
        drawTextLeft(c, paintText(18f, 0xFF888888.toInt()).setMono(), "  \u6765\u81ea\u300c\u7262\u767d\u64ad\u4e86\u5417\u300d", 110f, 510f)
        c.drawLine(80f, 600f, WIDTH - 80f, 600f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        drawTextLeft(c, paintText(20f, 0xFF424242.toInt()).setMono(), "\u4eca\u5929 \u00b7 11258892", 80f, 660f)
        drawQrCard(c, WIDTH / 2f, 760f, qrSize = 280f)
        return b
    }

    private fun renderReceipt(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        c.save()
        c.clipRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        val saw = Path()
        for (x in 0..(WIDTH / 14) + 1) {
            val px = x * 14f
            saw.moveTo(px, 0f); saw.lineTo(px + 7f, 7f); saw.lineTo(px + 14f, 0f)
            saw.moveTo(px, HEIGHT.toFloat()); saw.lineTo(px + 7f, HEIGHT - 7f); saw.lineTo(px + 14f, HEIGHT.toFloat())
        }
        c.drawPath(saw, Paint().apply { color = 0xFFE0E0E0.toInt() })
        c.restore()
        val mono = paintText(20f, 0xFF1A1A1A.toInt()).setMono()
        drawCenter(c, paintText(18f, 0xFF888888.toInt()).setMono(), "\u2605  RECEIPT  \u2605", WIDTH / 2f, 100f)
        c.drawLine(80f, 130f, WIDTH - 80f, 130f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 1f })
        drawTextLeft(c, mono, "ITEM              QTY    PRICE", 80f, 180f)
        c.drawLine(80f, 200f, WIDTH - 80f, 200f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        drawTextLeft(c, mono, "\u767d\u7eda\u76f4\u64ad\u95f4 \u76f4\u64ad\u76d1\u63a7", 80f, 240f)
        drawTextLeft(c, mono, "  - \u5b9e\u65f6\u72b6\u6001           1     0.00", 80f, 270f)
        drawTextLeft(c, mono, "  - \u8e72\u5f00\u64ad          24h     0.00", 80f, 300f)
        drawTextLeft(c, mono, "  - \u5f00\u64ad\u54cd\u94c3           -     0.00", 80f, 330f)
        c.drawLine(80f, 380f, WIDTH - 80f, 380f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        drawCenter(c, paintText(18f, 0xFF1A1A1A.toInt()).setMono(), "TOTAL  0.00", WIDTH / 2f, 420f)
        c.drawLine(80f, 460f, WIDTH - 80f, 460f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        drawTextLeft(c, mono, "ROOM   11258892", 80f, 500f)
        drawTextLeft(c, mono, "DATE   2026", 80f, 530f)
        drawTextLeft(c, mono, "STYLE  " + body, 80f, 560f)
        c.drawLine(80f, 700f, WIDTH - 80f, 700f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        c.drawLine(80f, 706f, WIDTH - 80f, 706f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        drawCenter(c, paintText(18f, 0xFF888888.toInt()).setMono(), "  \u611f\u8c22\u60e0\u987e  THANK YOU  ", WIDTH / 2f, 760f)
        drawQrCard(c, WIDTH / 2f, 820f, qrSize = 280f)
        return b
    }

    private fun renderLineArtMinimal(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAF5E6.toInt() })
        val cx = WIDTH / 2f
        val cy = 480f
        val r = 280f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF1A1A1A.toInt() }
        c.drawCircle(cx, cy, r, p)
        c.drawCircle(cx, cy, r + 20f, p)
        c.drawLine(cx - r, cy, cx + r, cy, p)
        c.drawLine(cx, cy - r, cx, cy + r, p)
        drawCenter(c, paintText(36f, 0xFF1A1A1A.toInt()).setSerif(), "R O O M   N o .  1 1 2 5 8 8 9 2", cx, 200f)
        drawCenter(c, paintText(20f, 0xFF424242.toInt()).setSerif(), "\u2014 waiting \u2014", cx, cy)
        drawCenter(c, paintText(28f, 0xFF1A1A1A.toInt()).setSerif(), headline.take(16), cx, 820f)
        drawCenterClipped(c, paintText(20f, 0xFF555555.toInt()).setSerif(), body, cx, 870f, 800f)
        drawQrCard(c, cx, 920f, qrSize = 280f)
        return b
    }

    private fun renderSingleGlyph(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        val isLive = isLiveFromHeadline(headline)
        val grad = if (isLive) intArrayOf(0xFFFFB199.toInt(), 0xFFFF6F61.toInt()) else intArrayOf(0xFFB3C5E0.toInt(), 0xFF6A7DA0.toInt())
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), grad[0], grad[1], Shader.TileMode.CLAMP)
        })
        val glyph = if (isLive) "\u770b" else "\u7b49"
        c.drawText(glyph, WIDTH / 2f, 800f, paintText(640f, 0xFFFFFFFF.toInt(), bold = true).apply { textAlign = Paint.Align.CENTER })
        drawCenterClipped(c, paintText(38f, 0xFFFFFFFF.toInt(), bold = true), headline.take(16), WIDTH / 2f, 900f, 900f)
        c.drawLine(WIDTH / 2f - 80, 940f, WIDTH / 2f + 80, 940f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        drawCenterClipped(c, paintText(22f, 0xFFFFFFFF.toInt()), body, WIDTH / 2f, 990f, 880f)
        drawQrCard(c, WIDTH / 2f, 1030f, qrSize = 220f)
        return b
    }
    // ===F3===
    private fun renderInkScroll(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F1E1.toInt() })
        drawRoundedCover(c, Rect(80, 200, WIDTH - 80, 580), cover, 0f, 0xFF6F4A2E.toInt())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() }
        c.save(); c.translate(200f, 720f); c.rotate(-12f); c.drawCircle(0f, 0f, 30f, ink); c.restore()
        c.save(); c.translate(880f, 760f); c.rotate(20f); c.drawCircle(0f, 0f, 22f, ink); c.restore()
        drawCenter(c, paintText(40f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 880f)
        drawCenterClipped(c, paintText(22f, 0xFF444444.toInt()), body, WIDTH / 2f, 940f, 880f)
        c.save(); c.translate(WIDTH - 200f, 1140f)
        c.drawCircle(0f, 0f, 60f, Paint().apply { color = 0xFFD32F2F.toInt() })
        c.drawText("  \u7262  \u767d  ", 0f, 12f, paintText(28f, 0xFFFFFFFF.toInt(), bold = true).apply { textAlign = Paint.Align.CENTER })
        c.restore()
        drawQrCard(c, WIDTH / 2f, 1010f, qrSize = 200f)
        return b
    }

    private fun renderSongGreen(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFFE8F1E0.toInt(), 0xFFB5C9A2.toInt(), Shader.TileMode.CLAMP)
        })
        val mountain = Path().apply {
            moveTo(0f, 600f); cubicTo(150f, 540f, 240f, 480f, 400f, 520f)
            cubicTo(540f, 560f, 640f, 460f, 800f, 500f)
            cubicTo(940f, 540f, 1020f, 580f, WIDTH.toFloat(), 540f); lineTo(WIDTH.toFloat(), 700f); lineTo(0f, 700f); close()
        }
        c.drawPath(mountain, Paint().apply { color = 0xFF6E8C5F.toInt() })
        val mountain2 = Path().apply {
            moveTo(0f, 700f); cubicTo(180f, 660f, 320f, 620f, 480f, 640f)
            cubicTo(640f, 660f, 780f, 700f, WIDTH.toFloat(), 680f); lineTo(WIDTH.toFloat(), 800f); lineTo(0f, 800f); close()
        }
        c.drawPath(mountain2, Paint().apply { color = 0xFF557349.toInt() })
        drawRoundedCover(c, Rect(240, 280, WIDTH - 240, 480), cover, 4f, 0xFF6E4A28.toInt())
        drawCenter(c, paintText(34f, 0xFF1A1A1A.toInt()), headline.take(22), WIDTH / 2f, 900f)
        drawCenterClipped(c, paintText(20f, 0xFF3A3A2F.toInt()), body, WIDTH / 2f, 960f, 880f)
        drawCenterClipped(c, paintText(16f, 0xFF6E4A28.toInt()), "\u2014\u2014  \u5c71 \u8fdc  \u2014\u2014", WIDTH / 2f, 1010f, 880f)
        drawQrCard(c, WIDTH / 2f, 1020f, qrSize = 220f, shadow = true)
        return b
    }
    private fun renderPaperCut(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt() })
        val cx = WIDTH / 2f
        val cy = 380f
        c.save(); c.clipRect(cx - 360, cy - 200, cx + 360, cy + 200)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFEBEE.toInt() })
        c.restore()
        val ink = Paint().apply { color = 0xFFD32F2F.toInt() }
        c.drawCircle(cx, cy - 130, 14f, ink)
        c.drawCircle(cx + 90, cy - 80, 14f, ink)
        c.drawCircle(cx - 90, cy - 80, 14f, ink)
        c.drawCircle(cx, cy + 130, 14f, ink)
        c.drawCircle(cx + 90, cy + 80, 14f, ink)
        c.drawCircle(cx - 90, cy + 80, 14f, ink)
        c.drawLine(cx - 360, cy, cx + 360, cy, Paint().apply { color = 0xFFD32F2F.toInt(); strokeWidth = 6f })
        c.drawLine(cx, cy - 200, cx, cy + 200, Paint().apply { color = 0xFFD32F2F.toInt(); strokeWidth = 6f })
        drawRoundedCover(c, RectF(cx - 240, cy - 180, cx + 240, cy + 160), cover, 0f, 0xFF6F4A2E.toInt())
        drawCenter(c, paintText(36f, 0xFF1A1A1A.toInt(), bold = true), headline.take(18), WIDTH / 2f, 700f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()), body, WIDTH / 2f, 760f, 880f)
        drawQrCard(c, WIDTH / 2f, 880f)
        return b
    }

    private fun renderSealFold(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8DCC4.toInt() })
        c.drawLine(WIDTH / 2f, 100f, WIDTH / 2f, HEIGHT - 100f, Paint().apply { color = 0xFF6F4A2E.toInt(); strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f) })
        drawRoundedCover(c, Rect(60, 220, 480, 600), cover, 4f, 0xFF6F4A2E.toInt())
        drawTextLeft(c, paintText(40f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), 540f, 300f)
        drawTextLeft(c, paintText(22f, 0xFF424242.toInt()), body, 540f, 360f)
        drawTextLeft(c, paintText(20f, 0xFF6F4A2E.toInt()), "\u2014  \u5ba3 \u7eb8  \u00b7  \u6298  \u2014", 540f, 700f)
        c.drawRect(820f, 180f, 980f, 340f, Paint().apply { color = 0xFFC62828.toInt() })
        c.save(); c.rotate(-6f, 900f, 260f)
        c.drawText("  \u7262  \u767d  ", 900f, 280f, paintText(46f, 0xFFFFFFFF.toInt(), bold = true).apply { textAlign = Paint.Align.CENTER })
        c.restore()
        drawQrCard(c, WIDTH / 2f, 800f, qrSize = 260f)
        return b
    }

    private fun renderFan(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFDF5.toInt() })
        val cx = WIDTH / 2f
        val cy = 600f
        val r = 460f
        val arc = 110f
        val fanPath = Path().apply {
            addArc(cx - r, cy - r, cx + r, cy + r, 90f - arc / 2, arc)
            lineTo(cx, cy + r * 0.92f); close()
        }
        c.save(); c.clipPath(fanPath)
        if (cover != null) c.drawBitmap(cover, null, Rect((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt()), null)
        else c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0E0.toInt() })
        c.restore()
        c.drawPath(fanPath, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF6F4A2E.toInt() })
        val spine = Paint().apply { color = 0xFF6F4A2E.toInt(); strokeWidth = 2f }
        for (i in -4..4) {
            val a = 90f + i * 18f
            val endX = cx + (r * 0.98f) * cos(Math.toRadians(a.toDouble())).toFloat()
            val endY = cy + (r * 0.98f) * sin(Math.toRadians(a.toDouble())).toFloat()
            c.drawLine(cx, cy, endX, endY, spine)
        }
        drawCenter(c, paintText(36f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 1010f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()), body, WIDTH / 2f, 1070f, 880f)
        drawQrCard(c, WIDTH / 2f, 1090f, qrSize = 160f, shadow = true)
        return b
    }
    // ===F4===
    private fun renderVinyl(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() })
        val cx = WIDTH / 2f; val cy = 480f; val r = 360f
        c.drawCircle(cx, cy, r, Paint().apply { color = 0xFF111111.toInt() })
        for (i in 1..6) c.drawCircle(cx, cy, r - i * 30f, Paint().apply { color = 0xFF1A1A1A.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.5f })
        if (cover != null) c.drawBitmap(cover, null, Rect((cx - 90f).toInt(), (cy - 90f).toInt(), (cx + 90f).toInt(), (cy + 90f).toInt()), null)
        c.drawCircle(cx, cy, 16f, Paint().apply { color = 0xFFE53935.toInt() })
        c.drawCircle(cx, cy, 4f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        val labelPath = Path().apply { addArc(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, 180f, 220f) }
        drawTextOnPathCenter(c, paintText(28f, 0xFFE0E0E0.toInt()), "\u767d  \u7eda  11258  LIVE  OR  DIE", labelPath, 16f)
        drawCenter(c, paintText(34f, 0xFFFAFAFA.toInt(), bold = true), headline.take(20), cx, 940f)
        drawCenterClipped(c, paintText(20f, 0xFFBDBDBD.toInt()), body, cx, 1000f, 880f)
        drawQrCard(c, cx, 1040f, qrSize = 220f)
        return b
    }

    private fun renderPolaroid(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0E0.toInt() })
        val cx = WIDTH / 2f
        val frame = RectF(cx - 380, 200f, cx + 380, 920f)
        c.drawRect(frame, Paint().apply { color = 0xFFFFFDF6.toInt() })
        val pic = RectF(cx - 340, 240f, cx + 340, 760f)
        c.drawRect(pic, Paint().apply { color = 0xFF424242.toInt() })
        drawRoundedCover(c, RectF(cx - 340f, 240f, cx + 340f, 760f), cover, 0f, 0xFF424242.toInt())
        drawTextLeft(c, paintText(36f, 0xFF1A1A1A.toInt(), bold = true), headline.take(12), cx - 360, 800f)
        drawTextLeft(c, paintText(22f, 0xFF6F4A2E.toInt()), body.take(18), cx - 360, 830f)
        drawTextLeft(c, paintText(18f, 0xFF9E9E9E.toInt()).setMono(), "11258  2026", cx + 240, 880f)
        drawTextLeft(c, paintText(16f, 0xFF9E9E9E.toInt()).setMono(), "INSTAX  SQ", cx + 240, 906f)
        drawCenter(c, paintText(40f, 0xFF1A1A1A.toInt(), bold = true), headline.take(14), WIDTH / 2f, 1000f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()), body, WIDTH / 2f, 1050f, 880f)
        drawQrCard(c, WIDTH / 2f, 1070f, qrSize = 180f, shadow = true)
        return b
    }

    private fun renderVhs(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0E0E0.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), 60f, Paint().apply { color = 0xFF1A1A1A.toInt() })
        c.drawRect(0f, 90f, WIDTH.toFloat(), 96f, Paint().apply { color = 0xFFB71C1C.toInt() })
        drawTextLeft(c, paintText(22f, 0xFFFFFFFF.toInt(), bold = true), "WHITE  ROOM  TAPE  No11258", 40f, 138f)
        drawRoundedCover(c, Rect(60, 220, 560, 720), cover, 4f, 0xFF424242.toInt())
        c.save(); c.rotate(-6f, 300f, 880f)
        drawTextLeft(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true), "\u7262\u767d  SP  \u25cf", 80f, 880f)
        drawTextLeft(c, paintText(20f, 0xFF1A1A1A.toInt()), body.take(18), 80f, 912f)
        c.restore()
        c.drawLine(620f, 220f, 620f, 1000f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 2f })
        drawTextLeft(c, paintText(18f, 0xFF1A1A1A.toInt()).setMono(), "DATE", 660f, 260f)
        drawTextLeft(c, paintText(20f, 0xFF1A1A1A.toInt()).setMono(), "11258", 660f, 290f)
        drawTextLeft(c, paintText(18f, 0xFF1A1A1A.toInt()).setMono(), "DURATION", 660f, 330f)
        drawTextLeft(c, paintText(20f, 0xFF1A1A1A.toInt()).setMono(), "24:00:00", 660f, 360f)
        drawTextLeft(c, paintText(18f, 0xFF1A1A1A.toInt()).setMono(), "SP  \u25cf", 660f, 400f)
        drawTextLeft(c, paintText(20f, 0xFFB71C1C.toInt(), bold = true), "\u25cf  REC", 660f, 430f)
        drawTextLeft(c, paintText(18f, 0xFF1A1A1A.toInt()).setMono(), "CH  11", 660f, 470f)
        drawTextLeft(c, paintText(20f, 0xFF1A1A1A.toInt()).setMono(), "BTQ", 660f, 500f)
        drawTextLeft(c, paintText(18f, 0xFF1A1A1A.toInt()).setMono(), "MADE  IN  X", 660f, 540f)
        drawCenter(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 1060f)
        drawCenterClipped(c, paintText(18f, 0xFF424242.toInt()), body, WIDTH / 2f, 1110f, 880f)
        drawQrCard(c, WIDTH / 2f, 1130f, qrSize = 140f, shadow = true)
        return b
    }

    private fun renderVintageTv(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() })
        val frame = RectF(60f, 140f, (WIDTH - 60f).toFloat(), 1000f)
        c.drawRoundRect(frame, 36f, 36f, Paint().apply { color = 0xFF424242.toInt() })
        val screen = RectF(100f, 200f, (WIDTH - 100f).toFloat(), 820f)
        c.drawRoundRect(screen, 20f, 20f, Paint().apply { color = 0xFF000000.toInt() })
        if (cover != null) {
            val tinted = cover.toTinted(0xFFFFE0B2.toInt(), 0.6f)
            c.save(); c.clipPath(Path().apply { addRoundRect(screen, 20f, 20f, Path.Direction.CW) })
            c.drawBitmap(tinted, null, Rect(screen.left.toInt(), screen.top.toInt(), screen.right.toInt(), screen.bottom.toInt()), null)
            c.restore(); tinted.recycle()
        }
        val glow = Paint().apply { color = 0xFF1A1A1A.toInt(); maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL) }
        c.drawLine(80f, 880f, 200f, 880f, glow)
        c.drawLine(80f, 900f, 200f, 900f, glow)
        c.drawText("CH 11258", 100f, 950f, paintText(28f, 0xFF8BC34A.toInt(), bold = true).setMono())
        drawCenter(c, paintText(20f, 0xFF757575.toInt()).setMono(), "\u25c0  \u25b6  \u2630  \u25c0  \u25b6", WIDTH / 2f, 1020f)
        // 标题/正文放在电视边框下方的深色区（浅色字），控件行其下，QR 压底
        drawCenter(c, paintText(30f, 0xFFFAFAFA.toInt(), bold = true), headline.take(20), WIDTH / 2f, 890f)
        drawCenterClipped(c, paintText(18f, 0xFFBDBDBD.toInt()), body, WIDTH / 2f, 940f, 880f)
        c.drawText("VOL", 140f, 1050f, paintText(20f, 0xFFBDBDBD.toInt()).setMono())
        c.drawText("CH", 240f, 1050f, paintText(20f, 0xFFBDBDBD.toInt()).setMono())
        c.drawText("OK", 340f, 1050f, paintText(20f, 0xFFBDBDBD.toInt()).setMono())
        c.drawText("12:34", WIDTH - 240f, 1050f, paintText(20f, 0xFFBDBDBD.toInt()).setMono())
        drawQrCard(c, WIDTH / 2f, 1090f, qrSize = 160f)
        return b
    }

    private fun renderClassicFilm(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5E6C2.toInt() })
        val noise = Paint()
        for (i in 0..800) {
            noise.color = 0x33000000.toInt()
            c.drawPoint((Math.random() * WIDTH).toFloat(), (Math.random() * HEIGHT).toFloat(), noise)
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), 96f, Paint().apply { color = 0xFF6B4423.toInt() })
        drawTextLeft(c, paintText(40f, 0xFFFFD54F.toInt(), bold = true), "VOL.X  \u00b7  ", 60f, 64f)
        drawTextLeft(c, paintText(28f, 0xFFFFD54F.toInt()).setSerif(), "\u2014 a film  by  \u767d\u7eda  \u2014", 280f, 64f)
        drawRoundedCover(c, Rect(160, 160, WIDTH - 160, 720), cover, 0f, 0xFF6F4A2E.toInt())
        drawGlow(c, WIDTH / 2f, 720f, 240f, 0xFFFFD54F.toInt(), 60)
        drawCenter(c, paintText(54f, 0xFF1A1A1A.toInt(), bold = true).setSerifBold(), headline.take(18), WIDTH / 2f, 880f)
        drawCenterClipped(c, paintText(24f, 0xFF424242.toInt()).setSerif(), body, WIDTH / 2f, 940f, 880f)
        drawCenterClipped(c, paintText(20f, 0xFF6B4423.toInt()).setSerif(), "\u2014\u2014  starring  \u7262\u767d  \u2014\u2014", WIDTH / 2f, 990f, 880f)
        drawQrCard(c, WIDTH / 2f, 1040f, qrSize = 240f)
        return b
    }
    // ===F5===
    private fun renderGlass(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        if (cover != null) {
            val blurred = centerCropTo(cover, WIDTH, HEIGHT)
            c.drawBitmap(blurred, 0f, 0f, null)
            blurred.recycle()
        } else c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A237E.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0x66000000 })
        val card = RectF(72f, 280f, (WIDTH - 72f).toFloat(), 1280f)
        val path = Path().apply { addRoundRect(card, 36f, 36f, Path.Direction.CW) }
        c.save(); c.clipPath(path); c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }); c.restore()
        c.drawPath(path, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x33FFFFFF.toInt() })
        drawCenter(c, paintText(42f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 420f)
        drawCenterClipped(c, paintText(22f, 0xFF444444.toInt()), body, WIDTH / 2f, 490f, 920f)
        drawQrCard(c, WIDTH / 2f, 970f, qrSize = 280f, shadow = true)
        return b
    }

    private fun renderAurora(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        val colors = intArrayOf(0xFF6A1B9A.toInt(), 0xFF1976D2.toInt(), 0xFF00BFA5.toInt(), 0xFFFFEE58.toInt(), 0xFF6A1B9A.toInt())
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { shader = SweepGradient(WIDTH / 2f, HEIGHT / 2f, colors, null) })
        val scan = Paint().apply { color = 0x33FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        for (i in 0..60) c.drawLine(0f, (i * (HEIGHT / 60f)), WIDTH.toFloat(), (i * (HEIGHT / 60f) + 8f), scan)
        drawCenter(c, paintText(48f, 0xFFFFFFFF.toInt(), bold = true), headline.take(18), WIDTH / 2f, 480f)
        drawCenterClipped(c, paintText(22f, 0xFFFFFFFF.toInt()), body, WIDTH / 2f, 540f, 880f)
        drawQrCard(c, WIDTH / 2f, 620f, qrSize = 240f)
        return b
    }

    private fun renderY2k(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEC407A.toInt() })
        for (i in 0..10) {
            val c1 = if (i % 2 == 0) 0xFFEC407A.toInt() else 0xFF40C4FF.toInt()
            c.drawRect(0f, (i * 135).toFloat(), WIDTH.toFloat(), (i * 135 + 60).toFloat(), Paint().apply { color = c1 })
        }
        drawRoundedCover(c, Rect(80, 300, 500, 720), cover, 12f, 0xFF40C4FF.toInt())
        for (i in 0..6) {
            val rot = (i * 15f) - 45f
            c.save(); c.translate(600f + (i % 3) * 90f, 300f + (i / 3) * 110f); c.rotate(rot)
            c.drawRect(-36f, -36f, 36f, 36f, Paint().apply {
                shader = LinearGradient(-36f, -36f, 36f, 36f,
                    if (i % 2 == 0) 0xFF40C4FF.toInt() else 0xFFFAFAFA.toInt(),
                    if (i % 2 == 0) 0xFFFAFAFA.toInt() else 0xFFEC407A.toInt(), Shader.TileMode.CLAMP)
            }); c.restore()
        }
        drawCenter(c, paintText(40f, 0xFFFFFFFF.toInt(), bold = true), headline.take(18), WIDTH / 2f, 900f)
        drawCenterClipped(c, paintText(20f, 0xFFFFFFFF.toInt()), body, WIDTH / 2f, 960f, 880f)
        drawCenterClipped(c, paintText(18f, 0xFFFFFFFF.toInt(), bold = true), "CYBER  11258", WIDTH / 2f, 1000f, 880f)
        drawQrCard(c, WIDTH / 2f, 1060f, qrSize = 220f)
        return b
    }

    private fun renderNeoBrutal(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDD835.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 36f })
        drawRoundedCover(c, Rect(80, 140, WIDTH - 80, 700), cover, 0f, 0xFF000000.toInt())
        drawCenter(c, paintText(48f, 0xFF000000.toInt(), bold = true), headline.take(18), WIDTH / 2f, 820f)
        drawCenterClipped(c, paintText(22f, 0xFF000000.toInt()), body, WIDTH / 2f, 880f, 880f)
        val r = RectF(WIDTH / 2f - 160, 940f, WIDTH / 2f + 160, 1260f)
        c.drawRect(r, Paint().apply { color = 0xFF000000.toInt() })
        c.drawRect(r.left + 12, r.top + 12, r.right - 12, r.bottom - 12, Paint().apply { color = 0xFFFFFFFF.toInt() })
        val qr = renderQr(280)
        c.drawBitmap(qr, r.left + 20, r.top + 20, null)
        qr.recycle()
        return b
    }

    private fun renderThreeD(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 0xFF311B92.toInt(), 0xFF7E57C2.toInt(), Shader.TileMode.CLAMP)
        })
        val cx = WIDTH / 2f; val cy = 600f; val baseY = cy + 40
        for (i in 0..5) {
            val off = i * 8f
            val alpha = 40 + i * 18
            c.drawRoundRect(RectF(cx - 280 - off, baseY - 240 - off, cx + 280 - off, baseY + 240 - off), 36f, 36f, Paint().apply { color = (alpha shl 24) or 0xFFFFFF })
        }
        drawRoundedCover(c, RectF(cx - 280, baseY - 280, cx + 280, baseY + 200), cover, 24f, 0xFF7E57C2.toInt())
        drawCenter(c, paintText(46f, 0xFFFFFFFF.toInt(), bold = true), headline.take(20), cx, 1020f)
        drawCenterClipped(c, paintText(22f, 0xFFEDE7F6.toInt()), body, cx, 1080f, 880f)
        drawQrCard(c, cx, 1110f, qrSize = 170f)
        return b
    }

    private fun renderMesh(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE0B2.toInt() })
        drawGlow(c, 200f, 240f, 380f, 0xFFEF5350.toInt(), 200)
        drawGlow(c, 880f, 300f, 420f, 0xFFFFB74D.toInt(), 180)
        drawGlow(c, 360f, 980f, 360f, 0xFFFFEE58.toInt(), 160)
        drawGlow(c, 820f, 1020f, 320f, 0xFFFFA726.toInt(), 200)
        drawRoundedCover(c, Rect(80, 200, WIDTH - 80, 760), cover, 24f, 0xFFD32F2F.toInt())
        drawCenter(c, paintText(48f, 0xFF1A1A1A.toInt(), bold = true), headline.take(18), WIDTH / 2f, 880f)
        drawCenterClipped(c, paintText(22f, 0xFF424242.toInt()), body, WIDTH / 2f, 940f, 880f)
        drawCenterClipped(c, paintText(18f, 0xFF6F4A2E.toInt()), "\u2014\u2014  GRADIENT  MESH  \u2014\u2014", WIDTH / 2f, 1000f, 880f)
        drawQrCard(c, WIDTH / 2f, 1020f, qrSize = 240f, shadow = true)
        return b
    }
    // ===F6===
    private fun renderHud(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0E1A.toInt() })
        val grid = Paint().apply { color = 0xFF1B2333.toInt(); strokeWidth = 1f }
        for (i in 0..WIDTH step 80) c.drawLine(i.toFloat(), 0f, i.toFloat(), HEIGHT.toFloat(), grid)
        for (i in 0..HEIGHT step 80) c.drawLine(0f, i.toFloat(), WIDTH.toFloat(), i.toFloat(), grid)
        val neon = Paint().apply { color = 0xFF00E676.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE }
        val sx = 60f; val sy = 60f; val ex = WIDTH - 60f; val ey = HEIGHT - 60f
        c.drawLine(sx, sy, sx + 80, sy, neon); c.drawLine(sx, sy, sx, sy + 80, neon)
        c.drawLine(ex, sy, ex - 80, sy, neon); c.drawLine(ex, sy, ex, sy + 80, neon)
        c.drawLine(sx, ey, sx + 80, ey, neon); c.drawLine(sx, ey, sx, ey - 80, neon)
        c.drawLine(ex, ey, ex - 80, ey, neon); c.drawLine(ex, ey, ex, ey - 80, neon)
        val bar = RectF(sx, 120f, WIDTH - sx, 200f)
        c.drawRoundRect(bar, 8f, 8f, Paint().apply { color = 0xFF1B2333.toInt() })
        c.drawRect(bar, Paint().apply { color = 0xFF00E676.toInt() })
        c.drawRect(sx + 4, 124f, (WIDTH - sx - 4) * 0.5f, 196f, Paint().apply { color = 0xFF00E676.toInt() })
        drawTextLeft(c, paintText(28f, 0xFF00E676.toInt(), bold = true).setMono(), "\u25ae\u25ae\u25ae\u25ae\u25ae\u25ae\u25ae\u25ae\u25af\u25af  HP  11258/24000", sx + 14, 170f)
        drawCenter(c, paintText(56f, 0xFFFAFAFA.toInt(), bold = true).setMono(), "\u258e LIVE 11258", WIDTH / 2f, 290f)
        drawRoundedCover(c, Rect(120, 360, WIDTH - 120, 760), cover, 6f, 0xFF00E676.toInt())
        drawCenterClipped(c, paintText(30f, 0xFF00E676.toInt()).setMono(), headline.take(20), WIDTH / 2f, 830f, 880f)
        drawCenterClipped(c, paintText(18f, 0xFF8BC34A.toInt()).setMono(), body, WIDTH / 2f, 880f, 880f)
        drawCenterClipped(c, paintText(16f, 0xFF00E676.toInt()).setMono(), "> SCAN QR TO JOIN LIVE", WIDTH / 2f, 940f, 880f)
        drawQrCard(c, WIDTH / 2f, 1000f, qrSize = 240f)
        return b
    }

    private fun renderPixel(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6D4C41.toInt() })
        if (cover != null) {
            val small = Bitmap.createScaledBitmap(cover, cover.width / 6, cover.height / 6, false)
            val big = Bitmap.createScaledBitmap(small, cover.width, cover.height, false)
            c.drawBitmap(big, 0f, 200f, null)
            small.recycle(); big.recycle()
        } else {
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B5E20.toInt() })
            for (i in 0..10) for (j in 0..10) c.drawRect(i * 108f + 200f, 220f + j * 60f, i * 108f + 280f, 220f + j * 60f + 60f, Paint().apply { color = 0xFF388E3C.toInt() })
        }
        val mono = paintText(32f, 0xFF1A1A1A.toInt()).setMono()
        drawCenter(c, paintText(48f, 0xFF1A1A1A.toInt(), bold = true).setMono(), "\u2593\u2593\u2593 LIVE  11258 \u2593\u2593\u2593", WIDTH / 2f, 130f)
        drawCenter(c, mono, headline.take(20), WIDTH / 2f, 920f)
        drawCenterClipped(c, paintText(20f, 0xFF1A1A1A.toInt()).setMono(), body, WIDTH / 2f, 980f, 880f)
        drawCenter(c, paintText(28f, 0xFFFFEB3B.toInt(), bold = true).setMono(), "\u25ae\u25ae\u25ae PRESS START \u25ae\u25ae\u25ae", WIDTH / 2f, 1060f)
        drawQrCard(c, WIDTH / 2f, 1100f, qrSize = 180f)
        return b
    }

    private fun renderCyber(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        val scan = Paint().apply { color = 0x3300FF66.toInt() }
        for (i in 0..HEIGHT step 4) c.drawLine(0f, i.toFloat(), WIDTH.toFloat(), i.toFloat(), scan)
        val mono = paintText(22f, 0xFF00FF66.toInt()).setMono()
        drawTextLeft(c, mono, "> stream.init(11258)", 60f, 80f)
        drawTextLeft(c, paintText(16f, 0xFF008844.toInt()).setMono(), "  status: scanning .............................  ", 60f, 110f)
        drawTextLeft(c, paintText(18f, 0xFF00FF66.toInt()).setMono(), "  [\u2593\u2593\u2593\u2593\u2593\u2593\u2593\u2593\u2591\u2591] 78%", 60f, 142f)
        c.drawRect(60f, 156f, WIDTH - 60f, 158f, Paint().apply { color = 0xFF00FF66.toInt() })
        c.drawRect(60f, 156f, (WIDTH - 60f) * 0.78f, 158f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        if (cover != null) {
            val gray = cover.toGrayscale()
            c.save(); c.clipRect(60, 200, WIDTH - 60, 700)
            c.drawBitmap(gray, null, Rect(60, 200, WIDTH - 60, 700), null)
            c.restore(); gray.recycle()
        } else c.drawRect(60f, 200f, (WIDTH - 60f).toFloat(), 700f, Paint().apply { color = 0xFF001100.toInt() })
        drawCenter(c, paintText(28f, 0xFF00FF66.toInt(), bold = true).setMono(), "\u258e SUBJECT  " + headline.take(20), WIDTH / 2f, 760f)
        drawCenterClipped(c, paintText(18f, 0xFF00AA55.toInt()).setMono(), body, WIDTH / 2f, 820f, 880f)
        drawCenter(c, paintText(16f, 0xFF00FF66.toInt()).setMono(), "// SCAN  TO  ENTER  LIVE  //", WIDTH / 2f, 880f)
        drawQrCard(c, WIDTH / 2f, 920f, qrSize = 240f)
        return b
    }

    private fun renderQrDominant(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAFAFA.toInt() })
        val cx = WIDTH / 2f
        val cy = HEIGHT / 2f - 40
        val qrSize = 720f
        val qr = renderQr(qrSize.toInt())
        c.drawBitmap(qr, cx - qrSize / 2, cy - qrSize / 2, null)
        qr.recycle()
        drawCenter(c, paintText(40f, 0xFF1A1A1A.toInt(), bold = true), headline.take(18), cx, cy + qrSize / 2 + 80f)
        drawCenterClipped(c, paintText(22f, 0xFF424242.toInt()), body, cx, cy + qrSize / 2 + 130f, 880f)
        return b
    }

    private fun renderAppStore(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() })
        c.drawRect(0f, 0f, WIDTH.toFloat(), 60f, Paint().apply { color = 0xFFFAFAFA.toInt() })
        drawTextLeft(c, paintText(20f, 0xFF1A1A1A.toInt()), "  App Store", 40f, 40f)
        drawTextLeft(c, paintText(16f, 0xFF888888.toInt()), "  \u7f16\u8f91\u63a8\u8350", 160f, 40f)
        c.drawLine(0f, 60f, WIDTH.toFloat(), 60f, Paint().apply { color = 0xFFE0E0E0.toInt() })
        val card = Path().apply { addRoundRect(RectF(80f, 110f, (WIDTH - 80f).toFloat(), 1140f), 24f, 24f, Path.Direction.CW) }
        c.drawPath(card, Paint().apply { color = 0xFFFFFFFF.toInt() })
        c.drawPath(card, Paint().apply { color = 0xFFE0E0E0.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f })
        drawRoundedCover(c, Rect(120, 150, 380, 410), cover, 24f, 0xFF6750A4.toInt())
        drawTextLeft(c, paintText(28f, 0xFF888888.toInt()), "\u7262\u767d\u64ad\u4e86\u5417", 420f, 180f)
        drawTextLeft(c, paintText(20f, 0xFF424242.toInt()), "\u767d\u7eda\u76f4\u64ad\u76d1\u63a7", 420f, 220f)
        drawTextLeft(c, paintText(16f, 0xFF888888.toInt()), "\u5de5\u5177  \u00b7  9+", 420f, 260f)
        drawTextLeft(c, paintText(14f, 0xFF888888.toInt()), "v1.5.1  \u00b7  14.2 MB  \u00b7  \u514d\u8d39", 420f, 290f)
        for (i in 0..4) c.drawText("\u2605", 420f + i * 28f, 340f, paintText(24f, 0xFFFFC107.toInt(), bold = true))
        drawTextLeft(c, paintText(14f, 0xFF1A1A1A.toInt()), "4.8", 560f, 340f)
        drawTextLeft(c, paintText(14f, 0xFF888888.toInt()), "(1.2k)", 590f, 340f)
        val accent = RectF(120f, 480f, (WIDTH - 120f).toFloat(), 600f)
        c.drawRect(accent, Paint().apply { color = 0xFFFAFAFA.toInt() })
        c.drawLine(accent.left, accent.top, accent.right, accent.top, Paint().apply { color = 0xFFE0E0E0.toInt() })
        drawTextLeft(c, paintText(22f, 0xFF1A1A1A.toInt(), bold = true), "\u65b0\u529f\u80fd", 140f, 540f)
        drawTextLeft(c, paintText(18f, 0xFF424242.toInt()), "\u5185\u6d4b\u7248\u5c1d\u9c9c\u00b7\u5206\u4eab\u4e09\u9009\u4e00\u00b7\u7cfb\u7edf insets \u9002\u914d", 140f, 575f)
        val desc = RectF(120f, 640f, (WIDTH - 120f).toFloat(), 920f)
        c.drawRect(desc, Paint().apply { color = 0xFFFAFAFA.toInt() })
        c.drawLine(desc.left, desc.top, desc.right, desc.top, Paint().apply { color = 0xFFE0E0E0.toInt() })
        drawTextLeft(c, paintText(22f, 0xFF1A1A1A.toInt(), bold = true), "\u4ecb\u7ecd", 140f, 700f)
        drawCenterClipped(c, paintText(18f, 0xFF424242.toInt()), body, WIDTH / 2f, 750f, 900f)
        drawCenterClipped(c, paintText(16f, 0xFF888888.toInt()), "\u2014\u2014 \u5f00\u53d1\u8005: \u7409\u7130\u537fOfficial \u2014\u2014", WIDTH / 2f, 800f, 900f)
        drawCenterClipped(c, paintText(14f, 0xFF888888.toInt()), "\ud83d\udd17 baicai.moe  \u00b7  github.com/XenoAmess/vivhite-tracker", WIDTH / 2f, 840f, 900f)
        drawTextLeft(c, paintText(16f, 0xFF888888.toInt()), "v1.0  \u00b7  2026", 140f, 900f)
        drawTextLeft(c, paintText(16f, 0xFF888888.toInt()), "\u9002\u5408 9+  \u00b7  \u5de5\u5177  \u00b7  \u4e2d\u6587", (WIDTH - 400f).toFloat(), 900f)
        c.drawRoundRect(RectF(160f, 1000f, (WIDTH - 160f).toFloat(), 1100f), 12f, 12f, Paint().apply { color = 0xFF0066CC.toInt() })
        drawCenter(c, paintText(28f, 0xFFFFFFFF.toInt(), bold = true), "\u5b89\u88c5", WIDTH / 2f, 1060f)
        return b
    }

    private fun renderRetroWeb(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB3E5FC.toInt() })
        c.drawRect(40f, 60f, WIDTH - 40f, HEIGHT - 60f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        c.drawRect(40f, 60f, WIDTH - 40f, 140f, Paint().apply { color = 0xFF1976D2.toInt() })
        drawTextLeft(c, paintText(20f, 0xFFFFFFFF.toInt()).setMono(), "  \u2605 \u767d\u7eda's Home Page  \u2606  white-room.live  \u2014\u2014 \u25a1 \u00d7", 60f, 110f)
        val marquee = RectF(40f, 170f, WIDTH - 40f, 240f)
        c.drawRect(marquee, Paint().apply { color = 0xFFFFEB3B.toInt() })
        drawCenter(c, paintText(36f, 0xFFB71C1C.toInt(), bold = true), "\u2605 \u2606 LIVE  NOW !!  \u6b22\u8fce\u6765\u5230\u767d\u7eda\u7684\u76f4\u64ad\u95f4  !! LIVE NOW \u2606 \u2605", WIDTH / 2f, 222f)
        drawRoundedCover(c, Rect(80, 280, 560, 600), cover, 4f, 0xFF42A5F5.toInt())
        drawTextLeft(c, paintText(40f, 0xFF0D47A1.toInt(), bold = true), "\u2606 " + headline.take(20) + " \u2606", 600f, 320f)
        drawTextLeft(c, paintText(20f, 0xFF1565C0.toInt()).apply { isUnderlineText = true }, "-> \u70b9\u51fb\u8fd9\u91cc\u8fdb\u5165\u76f4\u64ad\u95f4  <-", 600f, 380f)
        drawCenterClipped(c, paintText(18f, 0xFF1A1A1A.toInt()), body, 600f, 430f, 460f)
        c.drawRect(60f, 660f, 540f, 720f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        drawTextLeft(c, paintText(18f, 0xFF000000.toInt()).setMono(), "  \u8bbf\u5ba2\u8ba1\u6570:", 80f, 700f)
        drawTextLeft(c, paintText(24f, 0xFFB71C1C.toInt(), bold = true).setMono(), "11258892", 220f, 700f)
        drawTextLeft(c, paintText(18f, 0xFF0D47A1.toInt()).apply { isUnderlineText = true }, "\u00b7  [ \u5206\u4eab ]  [ \u5173\u4e8e\u767d\u7eda ]  [ QQ\u7fa4 ]  [ \u7559\u8a00\u677f ]", 60f, 780f)
        c.drawLine(40f, 1100f, WIDTH - 40f, 1100f, Paint().apply { color = 0xFF9E9E9E.toInt() })
        drawCenter(c, paintText(14f, 0xFF616161.toInt()).setMono(), "\u00a9 2026  \u7262\u767d\u64ad\u4e86\u5417  \u00b7  Made with \u2665 in GitHub Actions  \u00b7  Best viewed in 1024x768", WIDTH / 2f, 1170f)
        drawCenter(c, paintText(12f, 0xFF9E9E9E.toInt()).setMono(), "[This page has been viewed 11258892 times]", WIDTH / 2f, 1210f)
        return b
    }
    // ===F7===
    private fun renderDashboard(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0D1B2A.toInt() })
        for (i in 0..WIDTH step 40) c.drawLine(i.toFloat(), 0f, i.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0x11FFFFFF.toInt() })
        for (i in 0..HEIGHT step 40) c.drawLine(0f, i.toFloat(), WIDTH.toFloat(), i.toFloat(), Paint().apply { color = 0x11FFFFFF.toInt() })
        drawTextLeft(c, paintText(16f, 0xFF00B0FF.toInt(), bold = true).setMono(), "  > STREAM  MONITOR  v2.6", 32f, 56f)
        drawTextLeft(c, paintText(12f, 0xFF607D8B.toInt()).setMono(), "  2026  \u00b7  node 11258  \u00b7  ALIVE", 32f, 76f)
        val cards = listOf(
            Triple("STATUS", if (isLiveFromHeadline(headline)) "\u25cf LIVE" else "\u25cb IDLE", if (isLiveFromHeadline(headline)) 0xFF00E676.toInt() else 0xFFBDBDBD.toInt()),
            Triple("VIEWERS", "11258", 0xFF40C4FF.toInt()),
            Triple("UPTIME", "24h00m", 0xFFFFB74D.toInt()),
            Triple("PACKET", "0.00% LOSS", 0xFF00E676.toInt())
        )
        var cx = 40f
        for (c2 in cards) {
            val r = RectF(cx, 110f, cx + 240f, 240f)
            c.drawRoundRect(r, 8f, 8f, Paint().apply { color = 0xFF1B2333.toInt() })
            drawTextLeft(c, paintText(11f, 0xFF607D8B.toInt()).setMono(), c2.first, cx + 14, 138f)
            drawTextLeft(c, paintText(28f, c2.third, bold = true).setMono(), c2.second, cx + 14, 190f)
            c.drawLine(cx + 14, 212f, cx + 220, 212f, Paint().apply { color = 0xFF1B2333.toInt() })
            cx += 252
        }
        val line = Path()
        val bY = 420f
        for (i in 0..20) {
            val x = 60f + i * 48f
            val y = bY + 40f * sin((i / 20f * 4 * Math.PI).toFloat()) - 20f
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        c.drawPath(line, Paint().apply { color = 0xFF00B0FF.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE; pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 6f), 0f) })
        drawTextLeft(c, paintText(14f, 0xFF40C4FF.toInt(), bold = true).setMono(), "  viewer.peak(24h)", 60f, 500f)
        drawTextLeft(c, paintText(20f, 0xFFFAFAFA.toInt(), bold = true).setMono(), "  11258 \u25b2 +12%", 60f, 530f)
        drawRoundedCover(c, Rect(40, 580, 520, 880), cover, 0f, 0xFF263238.toInt())
        drawTextLeft(c, paintText(16f, 0xFF00E676.toInt(), bold = true).setMono(), "  \u25cf PREVIEW", 60f, 620f)
        c.drawRect(540f, 580f, (WIDTH - 40f).toFloat(), 880f, Paint().apply { color = 0xFF1B2333.toInt() })
        drawTextLeft(c, paintText(20f, 0xFFFAFAFA.toInt(), bold = true), headline.take(22), 560f, 620f)
        drawTextLeft(c, paintText(14f, 0xFF90A4AE.toInt()), body, 560f, 670f)
        drawTextLeft(c, paintText(12f, 0xFF00B0FF.toInt()).setMono(), "> scan to join  /  open  in  browser", 560f, 860f)
        drawTextLeft(c, paintText(14f, 0xFF90A4AE.toInt()).setMono(), "  ALERT  100%  \u00b7  LATENCY  0.3s  \u00b7  SRC  BILIBILI  \u00b7  v1.5.1", 40f, HEIGHT - 80f)
        drawQrCard(c, WIDTH / 2f, 920f, qrSize = 220f)
        return b
    }

    private fun renderEventTicket(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE082.toInt() })
        c.drawLine(0f, HEIGHT / 2f, WIDTH.toFloat(), HEIGHT / 2f, Paint().apply {
            color = 0xFF1A1A1A.toInt(); strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
        })
        c.drawCircle(0f, HEIGHT / 2f, 16f, Paint().apply { color = 0xFFFFE082.toInt() })
        c.drawCircle(0f, HEIGHT / 2f, 14f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        c.drawCircle(WIDTH.toFloat(), HEIGHT / 2f, 16f, Paint().apply { color = 0xFFFFE082.toInt() })
        c.drawCircle(WIDTH.toFloat(), HEIGHT / 2f, 14f, Paint().apply { color = 0xFFBDBDBD.toInt() })
        drawTextLeft(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true), "  ADMIT  ONE", 60f, 80f)
        drawTextLeft(c, paintText(16f, 0xFF1A1A1A.toInt()), "  \u00b7  2026  \u00b7  row 1  \u00b7  seat 11258", 60f, 110f)
        drawRoundedCover(c, Rect(60, 160, 480, 580), cover, 6f, 0xFF6F4A2E.toInt())
        drawTextLeft(c, paintText(40f, 0xFF1A1A1A.toInt(), bold = true), "STARRING", 540f, 200f)
        drawCenterClipped(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }, "\u767d  \u7eda", 750f, 240f, 460f)
        drawCenterClipped(c, paintText(22f, 0xFF1A1A1A.toInt()).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }, headline.take(20), 750f, 290f, 460f)
        drawCenterClipped(c, paintText(14f, 0xFF6F4A2E.toInt()).setSerif(), body, 750f, 360f, 460f)
        drawTextLeft(c, paintText(18f, 0xFF1A1A1A.toInt(), bold = true), "  No.11258892", 540f, 460f)
        drawTextLeft(c, paintText(14f, 0xFF1A1A1A.toInt()), "  ROW: A   SEAT: 1", 540f, 490f)
        drawTextLeft(c, paintText(14f, 0xFF1A1A1A.toInt()), "  DOOR  21:00", 540f, 520f)
        drawCenter(c, paintText(14f, 0xFF1A1A1A.toInt()).setMono(), "\u2605  \u767d  \u7eda  L I V E  \u2605", WIDTH / 2f, 620f)
        drawQrCard(c, WIDTH / 2f, 660f, qrSize = 280f)
        return b
    }

    private fun renderWeather(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFF90CAF9.toInt(), 0xFFE3F2FD.toInt(), Shader.TileMode.CLAMP)
        })
        val cx = WIDTH / 2f; val cy = 380f
        c.drawCircle(cx, cy, 160f, Paint().apply { color = 0xFFFFC107.toInt() })
        c.drawCircle(cx + 50, cy - 30, 130f, Paint().apply { color = 0xFF90CAF9.toInt() })
        for (i in 0..20) c.drawCircle(60f + (i * 53f) % WIDTH, 100f + (i * 73f) % 280, 4f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        drawCenter(c, paintText(36f, 0xFF1A237E.toInt(), bold = true), "11258  \u0261  open", cx, 680f)
        drawCenter(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true), "weather: \u76f4\u64ad\u672a\u5f00\u64ad", cx, 740f)
        drawCenter(c, paintText(22f, 0xFF424242.toInt()), "\u9884\u8ba1\u7b49 24h, \u4f53\u611f\u300c\u767d\u7eda\u300d", cx, 800f)
        drawCenterClipped(c, paintText(18f, 0xFF555555.toInt()), body, cx, 850f, 880f)
        drawCenterClipped(c, paintText(16f, 0xFF1A237E.toInt()), "UMI  /  white-room.live", cx, 900f, 880f)
        drawCenter(c, paintText(18f, 0xFF1A1A1A.toInt()), "\u2193 \u626b\u7801\u67e5\u770b\u5b9e\u65f6\u76f4\u64ad", cx, 1000f)
        drawQrCard(c, cx, 1050f, qrSize = 220f)
        return b
    }

    private fun renderNft(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 0xFF311B92.toInt(), 0xFF6A1B9A.toInt(), Shader.TileMode.CLAMP)
        })
        for (i in 0..12) for (j in 0..12) c.drawCircle(40f + i * 84f, 100f + j * 100f, 1.5f, Paint().apply { color = 0x44FFFFFF.toInt() })
        c.save(); c.translate(WIDTH / 2f, 380f); c.rotate(-2f)
        c.drawRoundRect(RectF(-360f, -300f, 360f, 280f), 24f, 24f, Paint().apply { color = 0xFFFF00FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f })
        if (cover != null) c.drawBitmap(cover, null, Rect(-360, -300, 360, 280), null)
        else c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A0033.toInt() })
        c.restore()
        c.drawRect(0f, 0f, WIDTH.toFloat(), 60f, Paint().apply { color = 0xFF000000.toInt() })
        drawTextLeft(c, paintText(22f, 0xFF00E676.toInt(), bold = true).setMono(), "  \u26d3  MINT  #11258  /  CONTRACT  white-room.live  \u26d3", 30f, 38f)
        drawCenter(c, paintText(18f, 0xFFFFD54F.toInt(), bold = true).setMono(), "RARITY  \u00b7  \u2605  \u2605  \u2605  \u2605  \u2605  \u00b7  LEGENDARY", WIDTH / 2f, 720f)
        drawCenter(c, paintText(28f, 0xFFFFFFFF.toInt(), bold = true), headline.take(22), WIDTH / 2f, 790f)
        drawCenterClipped(c, paintText(18f, 0xFFE1BEE7.toInt()), body, WIDTH / 2f, 840f, 880f)
        drawCenter(c, paintText(14f, 0xFFB39DDB.toInt()).setMono(), "OWNED  BY  LUREN  \u00b7  EDITION  1/1", WIDTH / 2f, 900f)
        drawCenter(c, paintText(11f, 0xFF9E9E9E.toInt()).setMono(), "0xba1c1n0ur4n920f11258...".take(38), WIDTH / 2f, 950f)
        drawQrCard(c, WIDTH / 2f, 1000f, qrSize = 240f)
        return b
    }

    private fun renderBoarding(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF8E7.toInt() })
        c.drawRect(40f, 80f, WIDTH - 40f, HEIGHT - 60f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        c.drawLine(WIDTH - 320f, 80f, WIDTH - 320f, HEIGHT - 60f, Paint().apply { color = 0xFF6F4A2E.toInt(); strokeWidth = 1.5f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f) })
        drawTextLeft(c, paintText(20f, 0xFF1A1A1A.toInt(), bold = true), "\u767b\u673a\u724c  /  BOARDING  PASS", 80f, 120f)
        drawTextLeft(c, paintText(14f, 0xFF6F4A2E.toInt()), "WHITE-ROOM AIRLINES  \u00b7  WRA", 80f, 148f)
        drawRoundedCover(c, Rect(80, 200, 480, 540), cover, 4f, 0xFF6F4A2E.toInt())
        drawCenterClipped(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 600f, 920f)
        drawCenterClipped(c, paintText(18f, 0xFF6F4A2E.toInt()), body, WIDTH / 2f, 650f, 920f)
        drawTextLeft(c, paintText(16f, 0xFF1A1A1A.toInt()), "\u822a\u73ed", 80f, 740f)
        drawTextLeft(c, paintText(24f, 0xFF1A1A1A.toInt(), bold = true), "BAI 11258", 80f, 770f)
        drawTextLeft(c, paintText(16f, 0xFF1A1A1A.toInt()), "\u5ea7\u4f4d", 80f, 830f)
        drawTextLeft(c, paintText(24f, 0xFF1A1A1A.toInt(), bold = true), "1A", 80f, 860f)
        drawTextLeft(c, paintText(16f, 0xFF1A1A1A.toInt()), "\u767b\u673a", 80f, 920f)
        drawTextLeft(c, paintText(24f, 0xFF1A1A1A.toInt(), bold = true), "21:00", 80f, 950f)
        drawTextLeft(c, paintText(16f, 0xFF1A1A1A.toInt()), "\u767b\u673a\u53e3", 80f, 1010f)
        drawTextLeft(c, paintText(24f, 0xFF1A1A1A.toInt(), bold = true), "\u767d  \u7eda", 80f, 1040f)
        c.drawLine(WIDTH - 290f, 200f, WIDTH - 60f, 200f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = 1f })
        for (i in 0..40) c.drawLine(WIDTH - 290f + i * 5.5f, 220f, WIDTH - 290f + i * 5.5f, 260f, Paint().apply { color = 0xFF1A1A1A.toInt(); strokeWidth = if (i % 3 == 0) 3f else 1.5f })
        drawTextLeft(c, paintText(14f, 0xFF1A1A1A.toInt()).setMono(), "PASSENGER  \u00b7  \u7409\u7130\u537f", WIDTH - 290f, 290f)
        drawTextLeft(c, paintText(14f, 0xFF1A1A1A.toInt()).setMono(), "CLASS  \u00b7  WHITEROOM+", WIDTH - 290f, 320f)
        drawTextLeft(c, paintText(14f, 0xFF1A1A1A.toInt()).setMono(), "SEQ  \u00b7  11258", WIDTH - 290f, 350f)
        drawTextLeft(c, paintText(14f, 0xFF1A1A1A.toInt()).setMono(), "GATE  \u00b7  \u7262\u767d", WIDTH - 290f, 380f)
        val qr = renderQr(200)
        c.drawBitmap(qr, WIDTH - 290f, 420f, null)
        qr.recycle()
        c.drawLine(WIDTH - 290f, 700f, WIDTH - 60f, 700f, Paint().apply { color = 0xFF6F4A2E.toInt(); strokeWidth = 1.5f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f) })
        drawTextLeft(c, paintText(11f, 0xFF6F4A2E.toInt()).setMono(), "\u2605  STUB  \u2605", WIDTH - 290f, 720f)
        drawTextLeft(c, paintText(11f, 0xFF6F4A2E.toInt()).setMono(), "white-room.live", WIDTH - 290f, 740f)
        drawTextLeft(c, paintText(11f, 0xFF6F4A2E.toInt()).setMono(), "2026  \u00b7  \u7262\u767d", WIDTH - 290f, 760f)
        c.drawText("Issued by  white-room.airline  \u00b7  white-room.live", WIDTH / 2f, 1100f, paintText(12f, 0xFF9E9E9E.toInt()).setMono().apply { textAlign = Paint.Align.CENTER })
        c.drawText("Not transferable  \u00b7  Not a receipt", WIDTH / 2f, 1130f, paintText(12f, 0xFF9E9E9E.toInt()).setMono().apply { textAlign = Paint.Align.CENTER })
        return b
    }
    // ===F8===
    private fun renderBirthday(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFCE4EC.toInt() })
        c.drawRect(0f, HEIGHT - 280f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0xFFFFCDD2.toInt() })
        val wave = Path()
        for (i in 0..15) {
            val cx = i * (WIDTH / 15f) + 36f
            wave.addCircle(cx, HEIGHT - 280f, 36f, Path.Direction.CW)
        }
        c.save(); c.clipOutPath(wave); c.drawRect(0f, HEIGHT - 280f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0xFFFFCDD2.toInt() }); c.restore()
        c.drawRect(0f, HEIGHT - 180f, WIDTH.toFloat(), HEIGHT - 100f, Paint().apply { color = 0xFFF8BBD0.toInt() })
        val wave2 = Path()
        for (i in 0..10) wave2.addCircle(i * (WIDTH / 10f) + 54f, HEIGHT - 180f, 30f, Path.Direction.CW)
        c.save(); c.clipOutPath(wave2); c.drawRect(0f, HEIGHT - 180f, WIDTH.toFloat(), HEIGHT - 100f, Paint().apply { color = 0xFFF8BBD0.toInt() }); c.restore()
        for (i in 0..2) {
            val cx = 480f + i * 80f
            c.drawRect(cx - 8, 800f, cx + 8, 880f, Paint().apply { color = 0xFFFFFFFF.toInt() })
            c.drawOval(cx - 12, 776f, cx + 12, 808f, Paint().apply { color = 0xFFFFA000.toInt() })
        }
        drawRoundedCover(c, Rect(280, 240, WIDTH - 280, 640), cover, 12f, 0xFFEC407A.toInt())
        drawCenter(c, paintText(40f, 0xFF880E4F.toInt(), bold = true), "\ud83c\udf82  \u767d\u7eda\u751f\u65e5\u8de4", WIDTH / 2f, 720f)
        drawCenter(c, paintText(28f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 900f)
        drawCenterClipped(c, paintText(20f, 0xFF6F4A2E.toInt()), body, WIDTH / 2f, 950f, 880f)
        drawCenter(c, paintText(16f, 0xFFAD1457.toInt()), "\ud83c\udf81  \u626b\u7801\u8bb8\u4e2a\u613f\u5427  \ud83c\udf81", WIDTH / 2f, 1010f)
        drawQrCard(c, WIDTH / 2f, 1040f, qrSize = 200f)
        return b
    }

    private fun renderHalloween(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFF311B92.toInt(), 0xFF1A0033.toInt(), Shader.TileMode.CLAMP)
        })
        drawGlow(c, WIDTH / 2f, 220f, 220f, 0xFFFFAB00.toInt(), 120)
        c.drawCircle(WIDTH / 2f, 220f, 110f, Paint().apply { color = 0xFFFFCC80.toInt() })
        for (i in 0..4) {
            val bx = 80f + i * 220f
            val by = 360f + (i % 2) * 50f
            c.save(); c.translate(bx, by)
            val bat = Path().apply {
                moveTo(0f, 0f)
                cubicTo(-30f, -20f, -40f, 10f, -50f, 0f)
                cubicTo(-40f, 10f, -40f, 5f, 0f, 8f)
                cubicTo(40f, 5f, 40f, 10f, 50f, 0f)
                cubicTo(40f, 10f, 30f, -20f, 0f, 0f)
            }
            c.drawPath(bat, Paint().apply { color = 0xFF1A1A1A.toInt() }); c.restore()
        }
        drawRoundedCover(c, Rect(180, 480, WIDTH - 180, 780), cover, 8f, 0xFF6A1B9A.toInt())
        drawCenter(c, paintText(46f, 0xFFFF6F00.toInt(), bold = true), "\ud83c\udf83 " + headline.take(20), WIDTH / 2f, 840f)
        drawCenterClipped(c, paintText(20f, 0xFFFFAB40.toInt()), body, WIDTH / 2f, 900f, 880f)
        drawCenter(c, paintText(16f, 0xFFFFCC80.toInt()), "\ud83e\udd87  \u626b\u7801\u6293\u767d\u7eda  \ud83e\udd87", WIDTH / 2f, 970f)
        drawQrCard(c, WIDTH / 2f, 1010f, qrSize = 220f)
        return b
    }

    private fun renderChristmas(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB71C1C.toInt() })
        for (i in 0..30) {
            val sx = (i * 47f) % WIDTH
            val sy = (i * 73f) % HEIGHT
            val s = 6f + (i % 3) * 4f
            c.drawLine(sx - s, sy, sx + s, sy, Paint().apply { color = 0xCCFFFFFF.toInt() })
            c.drawLine(sx, sy - s, sx, sy + s, Paint().apply { color = 0xCCFFFFFF.toInt() })
            c.drawLine(sx - s * 0.7f, sy - s * 0.7f, sx + s * 0.7f, sy + s * 0.7f, Paint().apply { color = 0xCCFFFFFF.toInt() })
            c.drawLine(sx - s * 0.7f, sy + s * 0.7f, sx + s * 0.7f, sy - s * 0.7f, Paint().apply { color = 0xCCFFFFFF.toInt() })
        }
        val cx = WIDTH / 2f; val baseY = 760f
        for (i in 0..2) {
            val r = 240f - i * 60f
            val top = baseY - i * 100f
            c.drawPath(Path().apply {
                moveTo(cx, top); lineTo(cx - r, top + 100f); lineTo(cx + r, top + 100f); close()
            }, Paint().apply { color = 0xFF1B5E20.toInt() })
            c.drawPath(Path().apply {
                moveTo(cx, top); lineTo(cx - r, top + 100f); lineTo(cx + r, top + 100f); close()
            }, Paint().apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f })
        }
        c.drawRect(cx - 30, baseY, cx + 30, baseY + 40, Paint().apply { color = 0xFF6D4C41.toInt() })
        c.drawCircle(cx, 460f, 14f, Paint().apply { color = 0xFFFFD54F.toInt() })
        c.drawCircle(cx - 80, 600f, 10f, Paint().apply { color = 0xFFE53935.toInt() })
        c.drawCircle(cx + 60, 650f, 10f, Paint().apply { color = 0xFF1E88E5.toInt() })
        c.drawCircle(cx - 40, 700f, 8f, Paint().apply { color = 0xFFFDD835.toInt() })
        drawRoundedCover(c, Rect(280, 200, WIDTH - 280, 440), cover, 8f, 0xFF388E3C.toInt())
        drawCenter(c, paintText(28f, 0xFFFFD54F.toInt(), bold = true), "\ud83c\udf84 " + headline.take(20), WIDTH / 2f, 850f)
        drawCenterClipped(c, paintText(20f, 0xFFFFCC80.toInt()), body, WIDTH / 2f, 900f, 880f)
        drawCenter(c, paintText(16f, 0xFFFFFFFF.toInt()), "\ud83c\udf81  \u626b\u7801\u9001\u793c  \ud83c\udf81", WIDTH / 2f, 970f)
        drawQrCard(c, WIDTH / 2f, 1010f, qrSize = 220f)
        return b
    }

    private fun renderValentine(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 0xFFF48FB1.toInt(), 0xFFEC407A.toInt(), Shader.TileMode.CLAMP)
        })
        for (i in 0..16) {
            val hx = (i * 67f + 40f) % WIDTH
            val hy = (i * 91f + 40f) % HEIGHT
            drawHeart(c, hx, hy, if (i % 2 == 0) 14f else 22f, 0x33FFFFFF.toInt())
        }
        drawHeart(c, WIDTH / 2f, 360f, 160f, 0xFFFFFFFF.toInt())
        drawHeart(c, WIDTH / 2f, 360f, 140f, 0xFFE91E63.toInt())
        drawCenter(c, paintText(40f, 0xFFFFFFFF.toInt(), bold = true), "LOVE", WIDTH / 2f, 380f)
        drawRoundedCover(c, Rect(160, 580, WIDTH - 160, 860), cover, 16f, 0xFFFFFFFF.toInt())
        drawCenterClipped(c, paintText(30f, 0xFFAD1457.toInt(), bold = true), headline.take(18), WIDTH / 2f, 900f, 880f)
        drawCenterClipped(c, paintText(18f, 0xFFEC407A.toInt()), body, WIDTH / 2f, 950f, 880f)
        drawCenter(c, paintText(16f, 0xFFFFFFFF.toInt()), "\u2661  \u626b\u7801\u4e00\u8d77\u770b\u767d\u7eda  \u2661", WIDTH / 2f, 1010f)
        drawQrCard(c, WIDTH / 2f, 1050f, qrSize = 200f)
        return b
    }

    private fun renderNewYear(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFF0D1B4A.toInt(), 0xFF1A0033.toInt(), Shader.TileMode.CLAMP)
        })
        for (i in 0..60) c.drawCircle((i * 53f) % WIDTH, (i * 91f) % HEIGHT, 2f, Paint().apply { color = 0xCCFFFFFF.toInt() })
        c.drawText("2026", WIDTH / 2f, 360f, paintText(200f, 0xFFFFD54F.toInt(), bold = true).apply { textAlign = Paint.Align.CENTER })
        c.drawLine(WIDTH / 2f - 200, 400f, WIDTH / 2f + 200, 400f, Paint().apply { color = 0xFFFFD54F.toInt(); strokeWidth = 3f })
        for (i in 0..5) {
            val a = (i * 60f) - 90f
            c.save(); c.translate(WIDTH / 2f, 280f); c.rotate(a); c.drawLine(0f, -160f, 0f, -90f, Paint().apply { color = 0xFFFFD54F.toInt(); strokeWidth = 2f })
            var fr = 30f; while (fr <= 70f) { c.drawLine(fr, -100f, fr - 5f, -100f - 5f, Paint().apply { color = 0xFFFFD54F.toInt(); strokeWidth = 2f }); fr += 10f }
            c.restore()
        }
        drawRoundedCover(c, Rect(180, 480, WIDTH - 180, 780), cover, 0f, 0xFF311B92.toInt())
        drawCenter(c, paintText(28f, 0xFFFFFFFF.toInt(), bold = true), headline.take(20), WIDTH / 2f, 840f)
        drawCenterClipped(c, paintText(18f, 0xFFFFFFFF.toInt()), body, WIDTH / 2f, 900f, 880f)
        drawCenter(c, paintText(16f, 0xFFFFD54F.toInt()), "\u2728  \u626b\u7801\u9001\u51fa\u53e4\u591c\u7684\u613f\u671b  \u2728", WIDTH / 2f, 970f)
        drawQrCard(c, WIDTH / 2f, 1010f, qrSize = 220f)
        return b
    }
    // ===F9===
    private fun renderWatercolor(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE0E6.toInt() })
        drawGlow(c, 200f, 250f, 400f, 0xFFF48FB1.toInt(), 80)
        drawGlow(c, 880f, 320f, 420f, 0xFF80DEEA.toInt(), 70)
        drawGlow(c, 300f, 900f, 380f, 0xFFFFF59D.toInt(), 60)
        drawGlow(c, 820f, 1050f, 360f, 0xFFA5D6A7.toInt(), 60)
        if (cover != null) c.drawBitmap(cover, null, Rect(120, 280, WIDTH - 120, 660), null)
        drawCenter(c, paintText(36f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 740f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()), body, WIDTH / 2f, 800f, 880f)
        c.drawLine(80f, 850f, WIDTH - 80f, 850f, Paint().apply { color = 0x33FFFFFF.toInt() })
        drawCenterClipped(c, paintText(16f, 0xFF6F4A2E.toInt()), "\u2014  watercolor  study  \u2014", WIDTH / 2f, 900f, 880f)
        drawQrCard(c, WIDTH / 2f, 940f, qrSize = 240f)
        return b
    }

    private fun renderDoodle(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFDF5.toInt() })
        val p = Paint().apply { color = 0xFF1A1A1A.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = null }
        val frame = Path()
        val rnd = java.util.Random(42)
        for (i in 0..30) {
            val x = (i * 60 + rnd.nextInt(20)).toFloat()
            val y = (i * 40 + rnd.nextInt(20)).toFloat()
            val w = 700f + rnd.nextInt(80)
            val h = 300f + rnd.nextInt(60)
            frame.moveTo(x, y)
            frame.lineTo(x + w, y + rnd.nextInt(10) - 5f)
            frame.lineTo(x + w + rnd.nextInt(20) - 10f, y + h)
            frame.lineTo(x + rnd.nextInt(20) - 10f, y + h)
            frame.lineTo(x, y + rnd.nextInt(10) - 5f)
        }
        c.drawPath(frame, p)
        drawTextLeft(c, paintText(40f, 0xFF1A1A1A.toInt(), bold = true).apply { letterSpacing = 0.08f }, "\u767d \u7eda  LIVE  11258", 100f, 150f)
        drawRoundedCover(c, Rect(140, 240, WIDTH - 140, 560), cover, 8f, 0xFF1A1A1A.toInt())
        drawCenter(c, paintText(36f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 640f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()), body, WIDTH / 2f, 700f, 880f)
        val pp = Paint().apply { color = 0xFF1A1A1A.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
        c.drawLine(120f, 780f, 360f, 740f, pp)
        c.drawLine(120f, 780f, 200f, 820f, pp)
        c.drawLine(360f, 740f, 280f, 800f, pp)
        drawCenterClipped(c, paintText(20f, 0xFF1A1A1A.toInt()), "~  ~  ~  \u626b\u7801\u770b  LIVE  ~  ~  ~", WIDTH / 2f, 880f, 880f)
        drawQrCard(c, WIDTH / 2f, 920f, qrSize = 220f)
        return b
    }

    private fun renderLensFlare(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() })
        drawGlow(c, 540f, 600f, 280f, 0xFFFF7043.toInt(), 150)
        drawGlow(c, 540f, 600f, 200f, 0xFFFFC107.toInt(), 120)
        drawGlow(c, 280f, 280f, 180f, 0xFF42A5F5.toInt(), 100)
        drawGlow(c, 800f, 380f, 160f, 0xFFAB47BC.toInt(), 90)
        drawGlow(c, 740f, 920f, 220f, 0xFF26C6DA.toInt(), 110)
        drawGlow(c, 320f, 980f, 180f, 0xFF66BB6A.toInt(), 80)
        c.drawLine(WIDTH / 2f - 200, 600f, WIDTH / 2f + 200, 600f, Paint().apply { color = 0x55FFFFFF.toInt(); strokeWidth = 1f })
        c.drawLine(540f, 260f, 540f, 940f, Paint().apply { color = 0x55FFFFFF.toInt(); strokeWidth = 1f })
        c.drawCircle(WIDTH / 2f, 600f, 30f, Paint().apply { color = 0xFFFFD54F.toInt() })
        c.drawCircle(WIDTH / 2f, 600f, 50f, Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f })
        drawCenter(c, paintText(40f, 0xFFFFFFFF.toInt(), bold = true), headline.take(20), WIDTH / 2f, 180f)
        drawCenterClipped(c, paintText(18f, 0xFFFFFFFF.toInt()), body, WIDTH / 2f, 240f, 880f)
        drawCenter(c, paintText(14f, 0xFFFFD54F.toInt()).setMono(), "LENS  f/1.4   ISO 400   1/60s", WIDTH / 2f, 280f)
        drawCenter(c, paintText(12f, 0xFFBDBDBD.toInt()).setMono(), "SHUTTER  \u2022  APERTURE  \u2022  FILM GRAIN", WIDTH / 2f, 1050f)
        drawQrCard(c, WIDTH / 2f, 1080f, qrSize = 200f)
        return b
    }

    private fun renderBioCell(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0F2F1.toInt() })
        val rnd = java.util.Random(7)
        for (i in 0..18) {
            val cx = (i * 71f + rnd.nextInt(40)) % WIDTH
            val cy = (i * 89f + rnd.nextInt(40)) % HEIGHT
            val r = 40f + rnd.nextInt(70)
            val alpha = 30 + rnd.nextInt(40)
            c.drawCircle(cx, cy, r, Paint().apply { color = (alpha shl 24) or 0x00897B7F })
            c.drawCircle(cx, cy, r * 0.5f, Paint().apply { color = ((alpha + 30) shl 24) or 0x00897B7F })
        }
        drawRoundedCover(c, Rect(180, 340, WIDTH - 180, 760), cover, 0f, 0xFF00695C.toInt())
        drawCenter(c, paintText(36f, 0xFF1A1A1A.toInt(), bold = true), headline.take(20), WIDTH / 2f, 840f)
        drawCenterClipped(c, paintText(20f, 0xFF424242.toInt()), body, WIDTH / 2f, 900f, 880f)
        drawCenter(c, paintText(14f, 0xFF00695C.toInt()).setMono(), "\u00d7  cell  \u00d7  mitosis  \u00d7  \u767d\u7eda  \u00d7", WIDTH / 2f, 960f)
        drawQrCard(c, WIDTH / 2f, 1000f, qrSize = 220f)
        return b
    }

    private fun renderBwFilm(cover: Bitmap?, headline: String, body: String): Bitmap {
        val b = newBitmap(); val c = Canvas(b)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF424242.toInt() })
        val rnd = java.util.Random(99)
        val grain = Paint()
        for (i in 0..2000) {
            val v = if (rnd.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            grain.color = (40 + rnd.nextInt(60)) shl 24 or (v and 0xFFFFFF)
            c.drawPoint(rnd.nextInt(WIDTH).toFloat(), rnd.nextInt(HEIGHT).toFloat(), grain)
        }
        if (cover != null) {
            val gray = cover.toGrayscale()
            c.drawBitmap(gray, null, Rect(100, 160, WIDTH - 100, 720), null)
            c.drawRect(100f, 160f, (WIDTH - 100f).toFloat(), 720f, Paint().apply { color = 0x33000000 })
            gray.recycle()
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), 100f, Paint().apply { color = 0xCC000000.toInt() })
        c.drawRect(0f, HEIGHT - 100f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0xCC000000.toInt() })
        drawCenter(c, paintText(36f, 0xFFFFFFFF.toInt(), bold = true).setSerif(), headline.take(20), WIDTH / 2f, 180f)
        drawCenterClipped(c, paintText(20f, 0xFFBDBDBD.toInt()).setSerif(), body, WIDTH / 2f, 230f, 880f)
        c.drawLine(WIDTH / 2f - 100, 780f, WIDTH / 2f + 100, 780f, Paint().apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2f })
        c.drawText("NO. 11258", WIDTH / 2f, 880f, paintText(16f, 0xFFBDBDBD.toInt()).setMono().apply { textAlign = Paint.Align.CENTER })
        c.drawText("EMULSION \u00b7 35mm \u00b7 TRI-X 400", WIDTH / 2f, 910f, paintText(16f, 0xFFBDBDBD.toInt()).setMono().apply { textAlign = Paint.Align.CENTER })
        drawCenter(c, paintText(12f, 0xFFBDBDBD.toInt()).setMono(), "\u2014  \u767d  \u7eda  photo studio  \u2014", WIDTH / 2f, 960f)
        drawCenter(c, paintText(11f, 0xFFBDBDBD.toInt()).setMono(), "WHITE  ROOM  \u00b7  TOKYO  \u00b7  EST  2026", WIDTH / 2f, 990f)
        drawQrCard(c, WIDTH / 2f, 1040f, qrSize = 200f)
        return b
    }

    private fun renderLightCard(cover: Bitmap?, headline: String, body: String): Bitmap {
        val bitmap = newBitmap()
        val canvas = Canvas(bitmap)
        canvas.drawRect(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFFF3EEFA.toInt(), 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
            }
        )
        drawRoundedCover(canvas, Rect(64, 48, WIDTH - 64, 560), cover, 32f, 0xFF6750A4.toInt())
        val isLive = isLiveFromHeadline(headline)
        drawBadge(canvas, WIDTH / 2f, 628f, isLive, darkText = false)
        drawCenter(canvas, paintText(52f, 0xFF1B1B1F.toInt(), bold = true), headline.take(24), WIDTH / 2f, 740f)
        drawCenter(canvas, paintText(34f, 0xFF44464F.toInt()), body.take(40), WIDTH / 2f, 812f)
        // QR 块高 368：上距 body 68px，下距页脚 66px，视觉居中
        drawQrCard(canvas, WIDTH / 2f, 880f, shadow = true)
        drawCenter(canvas, paintText(26f, 0xFF77777F.toInt()), "来自「牢白播了吗」· 白绮开播监控", WIDTH / 2f, HEIGHT - 36f)
        return bitmap
    }

    private fun renderBlurBg(cover: Bitmap?, headline: String, body: String): Bitmap {
        val bitmap = newBitmap()
        val canvas = Canvas(bitmap)
        if (cover != null) {
            val filled = centerCropTo(cover, WIDTH, HEIGHT)
            val tiny = Bitmap.createScaledBitmap(filled, WIDTH / 16, HEIGHT / 16, true)
            val blurred = Bitmap.createScaledBitmap(tiny, WIDTH, HEIGHT, true)
            canvas.drawBitmap(blurred, 0f, 0f, null)
            if (filled != cover) filled.recycle()
            tiny.recycle(); blurred.recycle()
        } else {
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6750A4.toInt() })
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = 0x66000000 })

        val card = RectF(84f, 190f, (WIDTH - 84).toFloat(), 1160f)
        canvas.drawRoundRect(card, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() })
        val isLive = isLiveFromHeadline(headline)
        drawBadge(canvas, WIDTH / 2f, 300f, isLive, darkText = false)
        drawCenter(canvas, paintText(50f, 0xFF1B1B1F.toInt(), bold = true), headline.take(24), WIDTH / 2f, 430f)
        drawCenter(canvas, paintText(32f, 0xFF44464F.toInt()), body.take(40), WIDTH / 2f, 506f)
        drawQrCard(canvas, WIDTH / 2f, 660f, shadow = true)
        drawCenter(canvas, paintText(26f, 0xEEFFFFFF.toInt()), "来自「牢白播了吗」· 白绮开播监控", WIDTH / 2f, HEIGHT - 48f)
        return bitmap
    }

    private fun renderDark(cover: Bitmap?, headline: String, body: String): Bitmap {
        val bitmap = newBitmap()
        val canvas = Canvas(bitmap)
        canvas.drawRect(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFF241E2E.toInt(), 0xFF171320.toInt(), Shader.TileMode.CLAMP)
            }
        )
        drawRoundedCover(canvas, Rect(64, 48, WIDTH - 64, 560), cover, 32f, 0xFF4A4458.toInt())
        val isLive = isLiveFromHeadline(headline)
        drawBadge(canvas, WIDTH / 2f, 628f, isLive, darkText = false)
        drawCenter(canvas, paintText(52f, 0xFFF2EFF7.toInt(), bold = true), headline.take(24), WIDTH / 2f, 740f)
        drawCenter(canvas, paintText(34f, 0xFFC9C5D0.toInt()), body.take(40), WIDTH / 2f, 812f)
        drawQrCard(canvas, WIDTH / 2f, 880f)
        drawCenter(canvas, paintText(26f, 0xFF8A8694.toInt()), "来自「牢白播了吗」· 白绮开播监控", WIDTH / 2f, HEIGHT - 36f)
        return bitmap
    }
}
