package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.bilibili.livemonitor.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 魔法期分享图渲染（1080×1350，4:5 海报，双主题）。
 *
 * - 在魔法期：angry 囚笼主题（深蓝灰）+ vivhite_angry 立绘
 * - 不在魔法期：happy 庆祝主题（粉彩）+ vivhite_happy 立绘
 *
 * 立绘以 36dp 圆角卡片嵌入（该圆角的圆角），配主题色描边与光晕托底。
 * 文案逻辑见 domain/MagicPeriodDecider.imageText（最新一条未结束 → 死了啦）。
 */
object MagicImageRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1350

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatRange(startMs: Long, endMs: Long): String =
        "${dateFormat.format(Date(startMs))}  ~  ${dateFormat.format(Date(endMs))}"

    /** 立绘卡尺寸（居中 860×860 圆角） */
    private const val ART_SIZE = 860
    private const val ART_RADIUS = 36f

    /**
     * @param isOngoing 最新魔法期是否未结束（true=死了啦 angry 主题，false=复活吧 happy 主题）
     * @param rangeText 日期区间文本（见 [formatRange]；无记录时调用方传占位文案）
     */
    fun render(context: Context, isOngoing: Boolean, rangeText: String): Bitmap {
        val renderer = PromoImageRenderer
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // ============ 主题基底 ============
        if (isOngoing) {
            // 深蓝灰渐变（贴合囚笼立绘色调）
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, HEIGHT.toFloat(),
                    0xFF141B24.toInt(), 0xFF2A3542.toInt(), Shader.TileMode.CLAMP
                )
            })
            // 低透明度竖条（呼应栏杆，克制不抢戏）
            val bar = Paint().apply { color = 0x143A4A5A.toInt() }
            for (i in 0..4) {
                val x = 140f + i * 220f
                c.drawRect(x, 0f, x + 26f, 980f, bar)
            }
        } else {
            // 粉奶油渐变（贴合庆祝立绘色调）
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, HEIGHT.toFloat(),
                    0xFFFFF1F4.toInt(), 0xFFFFD9E4.toInt(), Shader.TileMode.CLAMP
                )
            })
            // 粉金纸屑光斑
            renderer.drawGlow(c, 180f, 200f, 300f, 0xFFF48FB1.toInt(), 90)
            renderer.drawGlow(c, 900f, 260f, 280f, 0xFFFFD54F.toInt(), 70)
            renderer.drawGlow(c, 300f, 1120f, 320f, 0xFFF8BBD0.toInt(), 80)
        }

        // ============ 立绘卡（圆角裁切 + 描边 + 光晕） ============
        val artRes = if (isOngoing) R.drawable.vivhite_angry else R.drawable.vivhite_happy
        val art = BitmapFactory.decodeResource(context.resources, artRes)
        val artLeft = (WIDTH - ART_SIZE) / 2f
        val artTop = 60f
        val artRect = RectF(artLeft, artTop, artLeft + ART_SIZE, artTop + ART_SIZE)

        // 光晕托底
        if (isOngoing) {
            renderer.drawGlow(c, WIDTH / 2f, artTop + ART_SIZE / 2, 560f, 0xFF4A5A6A.toInt(), 110)
        } else {
            renderer.drawGlow(c, WIDTH / 2f, artTop + ART_SIZE / 2, 560f, 0xFFF48FB1.toInt(), 120)
        }

        // 圆角裁切绘制立绘
        val clipPath = Path().apply {
            addRoundRect(artRect, ART_RADIUS, ART_RADIUS, Path.Direction.CW)
        }
        c.save()
        c.clipPath(clipPath)
        val scaled = Bitmap.createScaledBitmap(art, ART_SIZE, ART_SIZE, true)
        c.drawBitmap(scaled, artLeft, artTop, null)
        scaled.recycle()
        c.restore()

        // 主题色描边
        c.drawRoundRect(
            artRect, ART_RADIUS, ART_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (isOngoing) 2f else 2.5f
                color = if (isOngoing) 0xFF4A5A6A.toInt() else 0xFFF48FB1.toInt()
            }
        )

        // ============ 文案区 ============
        val label = if (isOngoing) "⚰ 魔法期进行中" else "✨ 魔法期已结束"
        val labelColor = if (isOngoing) 0xFFB0BEC5.toInt() else 0xFFAD1457.toInt()
        renderer.drawCenter(
            c, renderer.paintText(30f, labelColor, bold = true),
            label, WIDTH / 2f, 1000f
        )

        val mainText = if (isOngoing) "死了啦，都怪你~" else "复活吧，我的爱人！"
        val mainColor = if (isOngoing) Color.WHITE else 0xFF880E4F.toInt()
        val titlePaint = renderer.paintText(
            if (mainText.length > 8) 66f else 84f,
            mainColor, bold = true
        )
        renderer.drawCenter(c, titlePaint, mainText, WIDTH / 2f, 1110f)

        // 区间卡（圆角半透明）
        val rangeLabel = if (isOngoing) "本次魔法期" else "上次魔法期"
        c.drawRoundRect(
            RectF(90f, 1150f, (WIDTH - 90).toFloat(), 1286f),
            20f, 20f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isOngoing) 0x14FFFFFF.toInt() else 0xB3FFFFFF.toInt()
            }
        )
        renderer.drawCenter(
            c, renderer.paintText(
                24f,
                if (isOngoing) 0xFF90A4AE.toInt() else 0xFFAD1457.toInt(),
                bold = true
            ),
            rangeLabel, WIDTH / 2f, 1196f
        )
        renderer.drawCenterClipped(
            c, renderer.paintText(
                28f,
                if (isOngoing) 0xFFFFFFFF.toInt() else 0xFF5D1049.toInt()
            ),
            rangeText, WIDTH / 2f, 1252f, 920f
        )

        // 落款
        renderer.drawCenter(
            c, renderer.paintText(
                22f,
                if (isOngoing) 0xFF78909C.toInt() else 0xCCAD1457.toInt()
            ),
            "白绮魔法期记录 · 来自「牢白播了吗」", WIDTH / 2f, HEIGHT - 24f
        )
        return bmp
    }
}
