package com.bilibili.livemonitor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 开播规律热力图（纯绘制无状态）：星期（行，日一…六）× 时段（列，0-5/6-11/12-17/18-23）。
 * 色块透明度 = 次数 / 最大次数；0 次画浅灰底。
 */
class WeekdayHourHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var heat: Array<IntArray> = Array(7) { IntArray(4) }

    private val accent = 0xFF6750A4.toInt()
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF999999.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setData(heat: Array<IntArray>) {
        require(heat.size == 7 && heat.all { it.size == 4 })
        this.heat = heat
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelW = dp(28).toFloat()
        val headerH = dp(18).toFloat()
        val gap = dp(4).toFloat()
        val cellW = (width - labelW - gap * (COLS + 1)) / COLS
        val cellH = (height - headerH - gap * (ROWS + 1)) / ROWS
        if (cellW <= 0 || cellH <= 0) return

        labelPaint.textSize = dp(10).toFloat()

        // 顶部时段标签
        SLOT_LABELS.forEachIndexed { col, label ->
            canvas.drawText(
                label,
                labelW + gap + col * (cellW + gap) + cellW / 2,
                headerH - dp(4).toFloat(),
                labelPaint
            )
        }

        val max = heat.maxOf { row -> row.max() }.coerceAtLeast(1)
        for (row in 0 until ROWS) {
            val y = headerH + gap + row * (cellH + gap)
            // 左侧星期标签
            canvas.drawText(
                WEEK_LABELS[row], labelW / 2, y + cellH / 2 + dp(4).toFloat(), labelPaint
            )
            for (col in 0 until COLS) {
                val count = heat[row][col]
                cellPaint.color = if (count == 0) {
                    0xFFF0F0F0.toInt()
                } else {
                    // 0.25~1.0 透明度渐变（次数越多越深）
                    val alpha = (0x40 + (0xBF * count / max)).coerceIn(0x40, 0xFF)
                    (alpha shl 24) or (accent and 0x00FFFFFF)
                }
                rect.set(labelW + gap + col * (cellW + gap), y,
                    labelW + gap + col * (cellW + gap) + cellW, y + cellH)
                canvas.drawRoundRect(rect, dp(4).toFloat(), dp(4).toFloat(), cellPaint)
                if (count > 0) {
                    canvas.drawText(
                        count.toString(),
                        rect.centerX(), rect.centerY() + dp(4).toFloat(),
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = 0xFFFFFFFF.toInt()
                            textSize = dp(10).toFloat()
                            textAlign = Paint.Align.CENTER
                        }
                    )
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ROWS = 7
        const val COLS = 4
        val WEEK_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")
        val SLOT_LABELS = listOf("0-5", "6-11", "12-17", "18-23")
    }
}
