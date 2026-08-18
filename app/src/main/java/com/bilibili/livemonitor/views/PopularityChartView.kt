package com.bilibili.livemonitor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 直播人气曲线（场次详情弹窗用，纯绘制无状态）。
 * X=时间，Y=在线人数；折线 + 峰值/均值线 + 起止时间与峰均值文字。
 */
class PopularityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points = listOf<Pair<Long, Int>>()
    private var startLabel: String? = null
    private var endLabel: String? = null

    private val accent = 0xFF6750A4.toInt()
    private val muted = 0xFF999999.toInt()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F6750A4.toInt()
        style = Paint.Style.FILL
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        textSize = 24f
    }
    private val linePath = Path()

    /** points: (ts ms, online) 升序；少于 2 个点不画线（由调用方提示无数据）。
     * startLabel/endLabel：覆盖横轴起止文字（月度曲线传日期，默认 HH:mm） */
    fun setData(data: List<Pair<Long, Int>>, startLabel: String? = null, endLabel: String? = null) {
        points = data
        this.startLabel = startLabel
        this.endLabel = endLabel
        contentDescription = if (data.size < 2) {
            "趋势图：数据不足"
        } else {
            val peak = data.maxOf { it.second }
            val average = data.sumOf { it.second } / data.size
            "趋势图，共${data.size}个数据点，峰值$peak，均值$average"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return
        val padL = dp(12).toFloat()
        val padR = dp(12).toFloat()
        val padT = dp(12).toFloat()
        val padB = dp(28).toFloat()
        val chartW = width - padL - padR
        val chartH = height - padT - padB
        if (chartW <= 0 || chartH <= 0) return

        val minTs = points.first().first
        val maxTs = points.last().first
        val maxOnline = points.maxOf { it.second }.coerceAtLeast(1)
        val minOnline = points.minOf { it.second }

        fun xOf(ts: Long): Float =
            padL + chartW * ((ts - minTs).toFloat() / (maxTs - minTs).coerceAtLeast(1L))
        fun yOf(online: Int): Float =
            padT + chartH * (1f - online.toFloat() / maxOnline)

        // 峰值/均值虚线参考
        val avg = points.sumOf { it.second } / points.size
        canvas.drawLine(padL, yOf(maxOnline), width - padR, yOf(maxOnline), guidePaint)
        canvas.drawLine(padL, yOf(avg), width - padR, yOf(avg), guidePaint)

        // 折线 + 下方填充
        linePath.reset()
        points.forEachIndexed { i, (ts, online) ->
            val x = xOf(ts)
            val y = yOf(online)
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        val fillPath = Path(linePath).apply {
            lineTo(xOf(maxTs), padT + chartH)
            lineTo(xOf(minTs), padT + chartH)
            close()
        }
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // 文字：左上峰值/均值（完整落在图表内，防顶边裁切），底部起止标签（可被调用方覆盖为日期等）
        canvas.drawText("峰值 $maxOnline · 均值 $avg", padL, padT + textPaint.textSize + dp(2).toFloat(), textPaint)
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val leftText = startLabel ?: fmt.format(java.util.Date(minTs))
        val rightText = endLabel ?: fmt.format(java.util.Date(maxTs))
        canvas.drawText(leftText, padL, height - dp(6).toFloat(), textPaint)
        canvas.drawText(rightText, width - padR - textPaint.measureText(rightText), height - dp(6).toFloat(), textPaint)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
