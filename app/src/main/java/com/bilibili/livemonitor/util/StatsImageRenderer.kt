package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.bilibili.livemonitor.views.WeekStreamBarsView

/**
 * 场次记录导出海报渲染（宽 1080，高度按内容计算，白底紫主题）。
 * 分区：标题 → 摘要卡 → 7 天柱状图（离屏复用 WeekStreamBarsView）→
 * 当月日历热力 → 本月心情统计 → 本月完整记录（场次+心情混排）→ 落款。
 * 纯绘制无 IO，数据全部由调用方组装。
 */
object StatsImageRenderer {

    const val WIDTH = 1080
    private const val PAD = 56f
    private const val CONTENT_W = WIDTH - PAD * 2

    private const val ACCENT = 0xFF6750A4.toInt()
    private const val ACCENT_SOFT = 0xFFF3EFFC.toInt()
    private const val MOOD_PINK = 0xFFF48FB1.toInt()
    private const val TEXT_MAIN = 0xFF1A1A1A.toInt()
    private const val TEXT_GRAY = 0xFF999999.toInt()

    /** 记录行：场次（紫条）或心情（粉条），text 已排版好单行展示 */
    data class RecordLine(val isSession: Boolean, val text: String)

    data class StatsImageData(
        val monthTitle: String,                    // "2026年8月"
        val summaryLines: List<String>,            // 摘要卡，逐行
        val barCounts: List<Int>,                  // 7 天柱状
        val barLabels: List<String>,
        val leading: Int,                          // 1 号前空格数（周日=0）
        val daysInMonth: Int,
        val sessionDays: Set<Int>,                 // 有场次的日（1..31）
        val todayDom: Int,                         // 今天的日；不在本月传 0
        val moodStats: List<Pair<String, Int>>,    // display to count，倒序
        val records: List<RecordLine>,
        val exportDate: String                     // "2026-08-10"
    )

    private const val HEADER_H = 150
    private const val SUMMARY_LINE_H = 44
    private const val SUMMARY_PAD_V = 24
    private const val SECTION_LABEL_H = 64
    private const val BARS_H = 360
    private const val CAL_HEADER_H = 44
    private const val CAL_CELL_H = 72
    private const val MOOD_LINE_H = 48
    private const val RECORD_ROW_H = 64
    private const val FOOTER_H = 90

    /** 海报总高度（纯计算，可单测）：各分区高度累加 */
    internal fun computeHeight(data: StatsImageData): Int {
        var h = HEADER_H
        h += SUMMARY_PAD_V * 2 + SUMMARY_LINE_H * data.summaryLines.size + 24
        h += SECTION_LABEL_H + BARS_H + 16
        val calRows = (data.leading + data.daysInMonth + 6) / 7
        h += SECTION_LABEL_H + CAL_HEADER_H + calRows * CAL_CELL_H + 16
        if (data.moodStats.isNotEmpty()) h += SECTION_LABEL_H + MOOD_LINE_H + 8
        h += SECTION_LABEL_H
        h += if (data.records.isEmpty()) RECORD_ROW_H else data.records.size * RECORD_ROW_H
        h += FOOTER_H
        return h
    }

    fun render(context: Context, data: StatsImageData): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, computeHeight(data), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val helper = PromoImageRenderer
        c.drawRect(0f, 0f, WIDTH.toFloat(), bmp.height.toFloat(), Paint().apply { color = 0xFFFFFFFF.toInt() })

        var y = 0f

        // ============ 标题栏 ============
        c.drawText(
            "牢白播了吗 · 场次记录", PAD, y + 76f,
            helper.paintText(46f, TEXT_MAIN, bold = true)
        )
        c.drawText(data.monthTitle, PAD, y + 124f, helper.paintText(28f, ACCENT, bold = true))
        y += HEADER_H
        c.drawLine(PAD, y - 14f, WIDTH - PAD, y - 14f, Paint().apply {
            color = 0xFFE0E0E0.toInt(); strokeWidth = 1.5f
        })

        // ============ 摘要卡 ============
        val cardH = SUMMARY_PAD_V * 2 + SUMMARY_LINE_H * data.summaryLines.size
        c.drawRoundRect(
            RectF(PAD, y, WIDTH - PAD, y + cardH), 24f, 24f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_SOFT }
        )
        var lineY = y + SUMMARY_PAD_V + 14f
        data.summaryLines.forEach { line ->
            helper.drawCenter(
                c, helper.paintText(28f, 0xFF3E2E63.toInt()),
                line, WIDTH / 2f, lineY + 14f
            )
            lineY += SUMMARY_LINE_H
        }
        y += cardH + 24f

        // ============ 最近 7 天柱状图（离屏复用 View 绘制） ============
        c.drawText(
            "最近 7 天开播场次", PAD, y + 40f,
            helper.paintText(26f, TEXT_MAIN, bold = true)
        )
        y += SECTION_LABEL_H
        if (data.barCounts.size == 7 && data.barLabels.size == 7) {
            val barsView = WeekStreamBarsView(context)
            barsView.setData(data.barCounts, data.barLabels)
            val wSpec = View.MeasureSpec.makeMeasureSpec(CONTENT_W.toInt(), View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(BARS_H, View.MeasureSpec.EXACTLY)
            barsView.measure(wSpec, hSpec)
            barsView.layout(0, 0, CONTENT_W.toInt(), BARS_H)
            c.save()
            c.translate(PAD, y)
            barsView.draw(c)
            c.restore()
        }
        y += BARS_H + 16f

        // ============ 当月日历热力 ============
        c.drawText(
            data.monthTitle, PAD, y + 40f,
            helper.paintText(26f, TEXT_MAIN, bold = true)
        )
        y += SECTION_LABEL_H
        val cellW = CONTENT_W / 7f
        val weekNames = listOf("日", "一", "二", "三", "四", "五", "六")
        weekNames.forEachIndexed { i, name ->
            helper.drawCenter(
                c, helper.paintText(22f, TEXT_GRAY),
                name, PAD + cellW * i + cellW / 2, y + 30f
            )
        }
        y += CAL_HEADER_H
        val dayPaint = helper.paintText(24f, TEXT_MAIN)
        for (d in 1..data.daysInMonth) {
            val idx = data.leading + d - 1
            val cx = PAD + cellW * (idx % 7) + cellW / 2
            val cy = y + (idx / 7) * CAL_CELL_H
            val hasSession = d in data.sessionDays
            if (hasSession) {
                c.drawRoundRect(
                    RectF(cx - 30f, cy + 4f, cx + 30f, cy + CAL_CELL_H - 8f), 12f, 12f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
                )
            }
            if (d == data.todayDom) {
                c.drawRoundRect(
                    RectF(cx - 30f, cy + 4f, cx + 30f, cy + CAL_CELL_H - 8f), 12f, 12f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = ACCENT
                    }
                )
            }
            dayPaint.color = if (hasSession) 0xFFFFFFFF.toInt() else TEXT_MAIN
            helper.drawCenter(c, dayPaint, d.toString(), cx, cy + CAL_CELL_H / 2 + 8f)
        }
        val calRows = (data.leading + data.daysInMonth + 6) / 7
        y += calRows * CAL_CELL_H + 16f

        // ============ 本月心情统计 ============
        if (data.moodStats.isNotEmpty()) {
            c.drawText("本月心情", PAD, y + 40f, helper.paintText(26f, TEXT_MAIN, bold = true))
            y += SECTION_LABEL_H
            val statsText = data.moodStats.joinToString("    ") { "${it.first} ×${it.second}" }
            helper.drawCenterClipped(
                c, helper.paintText(28f, TEXT_MAIN),
                statsText, WIDTH / 2f, y + 22f, CONTENT_W
            )
            y += MOOD_LINE_H + 8f
        }

        // ============ 本月完整记录（场次+心情混排） ============
        c.drawText(
            "本月记录 · ${data.records.size} 条", PAD, y + 40f,
            helper.paintText(26f, TEXT_MAIN, bold = true)
        )
        y += SECTION_LABEL_H
        if (data.records.isEmpty()) {
            c.drawText("（本月暂无记录）", PAD + 24f, y + 40f, helper.paintText(24f, TEXT_GRAY))
            y += RECORD_ROW_H
        } else {
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val recordPaint = helper.paintText(25f, TEXT_MAIN)
            data.records.forEach { record ->
                barPaint.color = if (record.isSession) ACCENT else MOOD_PINK
                c.drawRoundRect(RectF(PAD, y + 12f, PAD + 8f, y + RECORD_ROW_H - 12f), 4f, 4f, barPaint)
                var text = record.text
                while (recordPaint.measureText(text) > CONTENT_W - 32f && text.length > 1) {
                    text = text.dropLast(1)
                }
                if (text != record.text) text = text.dropLast(1) + "…"
                c.drawText(text, PAD + 24f, y + RECORD_ROW_H / 2 + 9f, recordPaint)
                y += RECORD_ROW_H
            }
        }

        // ============ 落款 ============
        helper.drawCenter(
            c, helper.paintText(22f, TEXT_GRAY),
            "白绮场次记录 · 来自「牢白播了吗」 · 导出 ${data.exportDate}",
            WIDTH / 2f, y + 50f
        )
        return bmp
    }
}
