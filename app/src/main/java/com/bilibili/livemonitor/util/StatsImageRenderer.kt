package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.bilibili.livemonitor.views.WeekStreamBarsView
import com.bilibili.livemonitor.views.WeekdayHourHeatmapView

/**
 * 绮迹手账导出海报渲染（宽 1080，高度按内容计算，白底紫主题）。
 * 分区：标题（含主播头像）→ 摘要卡 → 7 天柱状图（离屏复用 WeekStreamBarsView）→
 * 当月日历热力（场次紫底 / 魔法期粉底 / 重叠紫底粉描边）→ 本月心情统计 →
 * 本月魔法期统计 → 本月完整记录（场次/心情/魔法期混排）→ 落款。
 * 纯绘制无 IO，数据全部由调用方组装；avatar 为 null 时画占位圆。
 */
object StatsImageRenderer {

    const val WIDTH = 1080
    private const val PAD = 56f
    private const val CONTENT_W = WIDTH - PAD * 2

    private const val ACCENT = 0xFF6750A4.toInt()
    private const val ACCENT_SOFT = 0xFFF3EFFC.toInt()
    private const val MOOD_PINK = 0xFFF48FB1.toInt()
    private const val MAGIC_BG = 0xFFFCE4EC.toInt()
    private const val MAGIC_STROKE = 0xFFF48FB1.toInt()
    private const val MAGIC_BAR = 0xFF9E9E9E.toInt()
    private const val TEXT_MAIN = 0xFF1A1A1A.toInt()
    private const val TEXT_GRAY = 0xFF999999.toInt()

    /** 记录行类型：场次（紫条）/ 心情（粉条）/ 魔法期段（灰条） */
    enum class RecordKind { SESSION, MOOD, MAGIC }

    data class RecordLine(val kind: RecordKind, val text: String)

    data class StatsImageData(
        val monthTitle: String,                    // "2026年8月"
        val summaryLines: List<String>,            // 摘要卡，逐行
        val barsTitle: String,                     // 柱状图分区标题（「最近 7 天…」/「本月逐周场次」）
        val barCounts: List<Int>,                  // 柱状数据（柱数随长度）
        val barLabels: List<String>,
        val leading: Int,                          // 1 号前空格数（周日=0）
        val daysInMonth: Int,
        val sessionDays: Set<Int>,                 // 有场次的日（1..31）
        val magicDays: Set<Int>,                   // 魔法期覆盖的日（1..31）
        val todayDom: Int,                         // 今天的日；不在本月传 0
        val moodStats: List<Pair<String, Int>>,    // display to count，倒序
        val magicSummary: String?,                 // "本月魔法期：2 段 · 共 9 天"；无则 null
        // ---- 单月统计扩展区（为空则跳过不画） ----
        val weekdayHeat: Array<IntArray>? = null,   // 本月时段热力（7×4）
        val followerPoints: List<Pair<Long, Int>> = emptyList(),  // 本月粉丝曲线（ts→num）
        val dailyPopularity: List<Pair<Int, Int>> = emptyList(),  // 本月逐日人气峰值（日→峰值）
        val wordCloudWords: List<Pair<String, Int>> = emptyList(),  // 本月标题高频词
        val records: List<RecordLine>,
        val exportDate: String                     // "2026-08-10"
    )

    private const val HEADER_H = 150
    private const val AVATAR_SIZE = 96f
    private const val SUMMARY_LINE_H = 44
    private const val SUMMARY_PAD_V = 24
    private const val SECTION_LABEL_H = 64
    private const val BARS_H = 360
    private const val CAL_HEADER_H = 44
    private const val CAL_CELL_H = 72
    private const val MOOD_LINE_H = 48
    private const val RECORD_ROW_H = 64
    private const val FOOTER_H = 120
    // 扩展统计区（离屏 View 绘制）：热力图/曲线/词云统一高度
    private const val EXTRA_SECTION_H = 300
    private const val GAP_AFTER_EXTRA = 12

    // 海报最下方的不显眼小字（落款之下）
    private const val WHISPER_TEXT = "你会一直好好的，因为，我一直看着你呢。"

    // 各分区之后的间距（computeHeight 与 render 共用，改动必须同步）
    private const val GAP_AFTER_SUMMARY = 24
    private const val GAP_AFTER_BARS = 32
    private const val GAP_AFTER_CALENDAR = 16
    private const val GAP_AFTER_STATS = 8

    /** 海报总高度（各分区高度累加；词云按内容换行实测，需 context 建 View 量字） */
    internal fun computeHeight(context: Context, data: StatsImageData): Int {
        var h = HEADER_H
        h += SUMMARY_PAD_V * 2 + SUMMARY_LINE_H * data.summaryLines.size + GAP_AFTER_SUMMARY
        h += SECTION_LABEL_H + BARS_H + GAP_AFTER_BARS
        val calRows = (data.leading + data.daysInMonth + 6) / 7
        h += SECTION_LABEL_H + CAL_HEADER_H + calRows * CAL_CELL_H + GAP_AFTER_CALENDAR
        if (data.moodStats.isNotEmpty()) h += SECTION_LABEL_H + MOOD_LINE_H + GAP_AFTER_STATS
        if (data.magicSummary != null) h += MOOD_LINE_H + GAP_AFTER_STATS
        // 记录区（挪到扩展统计区之前）
        h += SECTION_LABEL_H
        h += if (data.records.isEmpty()) RECORD_ROW_H else data.records.size * RECORD_ROW_H
        // 扩展统计区（记录之后、落款之前）
        if (data.weekdayHeat != null) h += SECTION_LABEL_H + EXTRA_SECTION_H + GAP_AFTER_EXTRA
        if (data.followerPoints.size >= 2) h += SECTION_LABEL_H + EXTRA_SECTION_H + GAP_AFTER_EXTRA
        if (data.dailyPopularity.size >= 2) h += SECTION_LABEL_H + EXTRA_SECTION_H + GAP_AFTER_EXTRA
        if (data.wordCloudWords.isNotEmpty()) {
            val cloudView = com.bilibili.livemonitor.views.WordCloudView(context).apply {
                setData(data.wordCloudWords)
            }
            h += SECTION_LABEL_H + cloudView.computeContentHeight(CONTENT_W.toInt()) + GAP_AFTER_EXTRA
        }
        h += FOOTER_H
        return h
    }

    fun render(context: Context, data: StatsImageData, avatar: Bitmap? = null): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, computeHeight(context, data), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val helper = PromoImageRenderer
        c.drawRect(0f, 0f, WIDTH.toFloat(), bmp.height.toFloat(), Paint().apply { color = 0xFFFFFFFF.toInt() })

        var y = 0f

        // ============ 标题栏（左侧圆形头像 + 紫描边，失败占位紫底「白」） ============
        val avatarCx = PAD + AVATAR_SIZE / 2
        val avatarCy = 24f + AVATAR_SIZE / 2
        val avatarRect = RectF(PAD, 24f, PAD + AVATAR_SIZE, 24f + AVATAR_SIZE)
        if (avatar != null) {
            c.save()
            c.clipPath(Path().apply {
                addRoundRect(avatarRect, AVATAR_SIZE / 2, AVATAR_SIZE / 2, Path.Direction.CW)
            })
            val scaled = Bitmap.createScaledBitmap(
                avatar, AVATAR_SIZE.toInt(), AVATAR_SIZE.toInt(), true
            )
            c.drawBitmap(scaled, PAD, 24f, null)
            if (scaled !== avatar) scaled.recycle()
            c.restore()
        } else {
            c.drawCircle(avatarCx, avatarCy, AVATAR_SIZE / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ACCENT_SOFT
            })
            helper.drawCenter(
                c, helper.paintText(40f, ACCENT, bold = true),
                "白", avatarCx, avatarCy + 14f
            )
        }
        c.drawRoundRect(
            avatarRect, AVATAR_SIZE / 2, AVATAR_SIZE / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3f; color = ACCENT
            }
        )
        val titleX = PAD + AVATAR_SIZE + 20f
        c.drawText(
            "绮迹手账", titleX, y + 76f,
            helper.paintText(46f, TEXT_MAIN, bold = true)
        )
        c.drawText(data.monthTitle, titleX, y + 124f, helper.paintText(28f, ACCENT, bold = true))
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
        y += cardH + GAP_AFTER_SUMMARY

        // ============ 柱状图（离屏复用 View 绘制，柱数随数据） ============
        c.drawText(
            data.barsTitle, PAD, y + 40f,
            helper.paintText(26f, TEXT_MAIN, bold = true)
        )
        y += SECTION_LABEL_H
        if (data.barCounts.isNotEmpty() && data.barCounts.size == data.barLabels.size) {
            val barsView = WeekStreamBarsView(context)
            barsView.setData(data.barCounts, data.barLabels)
            val wSpec = View.MeasureSpec.makeMeasureSpec(CONTENT_W.toInt(), View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(BARS_H, View.MeasureSpec.EXACTLY)
            barsView.measure(wSpec, hSpec)
            barsView.layout(0, 0, CONTENT_W.toInt(), BARS_H)
            c.save()
            c.translate(PAD, y)
            // 离屏无父布局裁剪：显式 clip，防止 view 内部绘制越界污染其他分区
            c.clipRect(0f, 0f, CONTENT_W, BARS_H.toFloat())
            barsView.draw(c)
            c.restore()
        }
        y += BARS_H + GAP_AFTER_BARS

        // ============ 当月日历热力（场次紫底 / 魔法期粉底 / 重叠紫底粉描边） ============
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
            val hasMagic = d in data.magicDays
            val cellRect = RectF(cx - 30f, cy + 4f, cx + 30f, cy + CAL_CELL_H - 8f)
            when {
                hasSession -> {
                    c.drawRoundRect(cellRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ACCENT
                    })
                    if (hasMagic) {
                        c.drawRoundRect(cellRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = 3f; color = MAGIC_STROKE
                        })
                    }
                }
                hasMagic -> {
                    c.drawRoundRect(cellRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = MAGIC_BG
                    })
                }
            }
            if (d == data.todayDom) {
                c.drawRoundRect(cellRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2.5f; color = ACCENT
                })
            }
            dayPaint.color = if (hasSession) 0xFFFFFFFF.toInt() else TEXT_MAIN
            helper.drawCenter(c, dayPaint, d.toString(), cx, cy + CAL_CELL_H / 2 + 8f)
        }
        val calRows = (data.leading + data.daysInMonth + 6) / 7
        y += calRows * CAL_CELL_H + GAP_AFTER_CALENDAR

        // ============ 本月心情统计 + 魔法期统计 ============
        if (data.moodStats.isNotEmpty()) {
            c.drawText("本月心情", PAD, y + 40f, helper.paintText(26f, TEXT_MAIN, bold = true))
            y += SECTION_LABEL_H
            val statsText = data.moodStats.joinToString("    ") { "${it.first} ×${it.second}" }
            helper.drawCenterClipped(
                c, helper.paintText(28f, TEXT_MAIN),
                statsText, WIDTH / 2f, y + 22f, CONTENT_W
            )
            y += MOOD_LINE_H + GAP_AFTER_STATS
        }
        data.magicSummary?.let { magic ->
            helper.drawCenter(
                c, helper.paintText(26f, 0xFFAD1457.toInt(), bold = true),
                magic, WIDTH / 2f, y + 26f
            )
            y += MOOD_LINE_H + GAP_AFTER_STATS
        }

        // ============ 本月完整记录（场次/心情/魔法期混排） ============
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
                barPaint.color = when (record.kind) {
                    RecordKind.SESSION -> ACCENT
                    RecordKind.MOOD -> MOOD_PINK
                    RecordKind.MAGIC -> MAGIC_BAR
                }
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

        // ============ 扩展统计区（挪到记录之后、落款之前；词云高度自适应） ============
        fun drawOffscreen(view: View, height: Int = EXTRA_SECTION_H) {
            val wSpec = View.MeasureSpec.makeMeasureSpec(CONTENT_W.toInt(), View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            view.measure(wSpec, hSpec)
            view.layout(0, 0, CONTENT_W.toInt(), height)
            c.save()
            c.translate(PAD, y)
            c.clipRect(0f, 0f, CONTENT_W, height.toFloat())
            view.draw(c)
            c.restore()
            y += height + GAP_AFTER_EXTRA
        }

        data.weekdayHeat?.let { heat ->
            c.drawText("本月开播时段分布", PAD, y + 40f, helper.paintText(26f, TEXT_MAIN, bold = true))
            y += SECTION_LABEL_H
            drawOffscreen(WeekdayHourHeatmapView(context).apply { setData(heat) })
        }
        if (data.followerPoints.size >= 2) {
            c.drawText("本月粉丝变化", PAD, y + 40f, helper.paintText(26f, TEXT_MAIN, bold = true))
            y += SECTION_LABEL_H
            val dayFmt = java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())
            drawOffscreen(
                com.bilibili.livemonitor.views.PopularityChartView(context).apply {
                    setData(
                        data.followerPoints,
                        startLabel = dayFmt.format(java.util.Date(data.followerPoints.first().first)),
                        endLabel = dayFmt.format(java.util.Date(data.followerPoints.last().first))
                    )
                }
            )
        }
        if (data.dailyPopularity.size >= 2) {
            c.drawText("本月人气峰值", PAD, y + 40f, helper.paintText(26f, TEXT_MAIN, bold = true))
            y += SECTION_LABEL_H
            // monthTitle 形如 "2026年8月" → 取 "8月"
            val monthLabel = data.monthTitle.substringAfter("年")
            drawOffscreen(
                com.bilibili.livemonitor.views.PopularityChartView(context).apply {
                    setData(
                        data.dailyPopularity.map { (dom, peak) -> dom.toLong() to peak },
                        startLabel = "$monthLabel${data.dailyPopularity.first().first}日",
                        endLabel = "$monthLabel${data.dailyPopularity.last().first}日"
                    )
                }
            )
        }
        if (data.wordCloudWords.isNotEmpty()) {
            c.drawText("本月标题高频词", PAD, y + 40f, helper.paintText(26f, TEXT_MAIN, bold = true))
            y += SECTION_LABEL_H
            val cloudView = com.bilibili.livemonitor.views.WordCloudView(context).apply {
                setData(data.wordCloudWords)
            }
            // 按内容换行后的真实高度绘制，不再裁断
            drawOffscreen(cloudView, cloudView.computeContentHeight(CONTENT_W.toInt()))
        }

        // ============ 落款 ============
        helper.drawCenter(
            c, helper.paintText(22f, TEXT_GRAY),
            "白绮的绮迹手账 · 来自「牢白播了吗」 · 导出 ${data.exportDate}",
            WIDTH / 2f, y + 50f
        )
        // 耳语小字（不显眼：更浅灰 + 更小字号）
        helper.drawCenter(
            c, helper.paintText(18f, 0xFFBBBBBB.toInt()),
            WHISPER_TEXT, WIDTH / 2f, y + 92f
        )
        return bmp
    }
}
