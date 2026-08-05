package com.bilibili.livemonitor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 最近 7 天开播场次柱状图（纯绘制，无状态逻辑）。
 * 数据通过 [setData] 注入：7 个柱 + 7 个底部标签；柱顶显示场次数。
 */
class WeekStreamBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val counts = IntArray(BAR_COUNT)
    private val labels = Array(BAR_COUNT) { "" }
    private val accent = 0xFF6750A4.toInt()
    private val muted = 0x666750A4.toInt()
    private val labelColor = 0xFF999999.toInt()

    fun setData(counts: List<Int>, labels: List<String>) {
        require(counts.size == BAR_COUNT && labels.size == BAR_COUNT)
        counts.forEachIndexed { i, v -> this.counts[i] = v }
        labels.forEachIndexed { i, v -> this.labels[i] = v }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val max = counts.maxOrNull()?.takeIf { it > 0 } ?: 1
        val gap = dp(8).toFloat()
        val labelH = dp(14).toFloat()
        val countH = dp(14).toFloat()
        val chartBottom = height - labelH
        val barW = (width - gap * (BAR_COUNT + 1)) / BAR_COUNT

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val corner = dp(3).toFloat()

        counts.forEachIndexed { i, count ->
            val x = gap + i * (barW + gap)
            val h = if (count == 0) dp(3).toFloat() else (chartBottom - countH) * count / max
            barPaint.color = if (count == 0) muted else accent
            canvas.drawRoundRect(
                RectF(x, chartBottom - countH - h, x + barW, chartBottom - countH), corner, corner, barPaint
            )
            // 柱顶场次数
            val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dp(12).toFloat()
                color = accent
                textAlign = Paint.Align.CENTER
            }
            if (count > 0) {
                canvas.drawText(
                    count.toString(), x + barW / 2, chartBottom - countH - h - dp(2), countPaint
                )
            }
            // 底部标签
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dp(11).toFloat()
                color = labelColor
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(labels[i], x + barW / 2, (height - dp(2)).toFloat(), labelPaint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BAR_COUNT = 7
    }
}
