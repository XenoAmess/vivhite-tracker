package com.bilibili.livemonitor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 魔法期分享图渲染（1080×800，专用简洁矮版式）。
 *
 * 版式：紫粉渐变底 + 大文案（死了啦，都怪你~ / 复活吧，我的爱人！）
 * + 魔法期日期区间 + 落款「白绮魔法期记录 · 来自牢白播了吗」。
 * 不展示直播间二维码（用户反馈 2026-08-02）。
 *
 * 文案逻辑见 domain/MagicPeriodDecider.imageText（最新一条未结束 → 死了啦）。
 */
object MagicImageRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 800

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatRange(startMs: Long, endMs: Long): String =
        "${dateFormat.format(Date(startMs))}  ~  ${dateFormat.format(Date(endMs))}"

    /**
     * @param isOngoing 最新魔法期是否未结束（true=死了啦，false=复活吧）
     * @param rangeText 日期区间文本（见 [formatRange]；无记录时调用方传占位文案）
     */
    fun render(isOngoing: Boolean, rangeText: String): Bitmap {
        val renderer = PromoImageRenderer
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // 紫粉渐变底（未开播死亡期=暗紫，复活=亮紫粉）
        val top = if (isOngoing) 0xFF4A148C.toInt() else 0xFF7B1FA2.toInt()
        val bottom = if (isOngoing) 0xFF1A0033.toInt() else 0xFFF48FB1.toInt()
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })

        // 光斑装饰
        renderer.drawGlow(c, 220f, 180f, 320f, 0xFFFF80AB.toInt(), 90)
        renderer.drawGlow(c, 860f, 220f, 280f, 0xFFB388FF.toInt(), 80)
        renderer.drawGlow(c, 540f, 640f, 340f, 0xFFEA80FC.toInt(), 70)

        // 顶部小标签
        val label = if (isOngoing) "⚰ 魔法期进行中" else "✨ 魔法期已结束"
        renderer.drawCenter(
            c, renderer.paintText(30f, 0xCCFFFFFF.toInt(), bold = true),
            label, WIDTH / 2f, 150f
        )

        // 主文案（大字）
        val mainText = if (isOngoing) "死了啦，都怪你~" else "复活吧，我的爱人！"
        val titlePaint = renderer.paintText(
            if (mainText.length > 8) 66f else 84f,
            Color.WHITE, bold = true
        )
        renderer.drawCenter(c, titlePaint, mainText, WIDTH / 2f, 380f)

        // 副标题（白绮的魔法期）
        renderer.drawCenter(
            c, renderer.paintText(32f, 0xE6FFFFFF.toInt()),
            "——  白绮的魔法期记录  ——", WIDTH / 2f, 460f
        )

        // 日期区间卡片
        c.drawRoundRect(
            android.graphics.RectF(100f, 530f, (WIDTH - 100).toFloat(), 690f),
            24f, 24f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }
        )
        renderer.drawCenter(
            c, renderer.paintText(26f, 0xFFFFFFFF.toInt(), bold = true),
            "魔法期区间", WIDTH / 2f, 590f
        )
        renderer.drawCenterClipped(
            c, renderer.paintText(28f, 0xFFFFFFFF.toInt()),
            rangeText, WIDTH / 2f, 650f, 920f
        )

        // 落款
        renderer.drawCenter(
            c, renderer.paintText(24f, 0xB3FFFFFF.toInt()),
            "白绮魔法期记录 · 来自「牢白播了吗」", WIDTH / 2f, HEIGHT - 34f
        )
        return bmp
    }
}
