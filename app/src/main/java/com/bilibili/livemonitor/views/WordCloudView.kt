package com.bilibili.livemonitor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 标题高频词云（纯绘制）：按频次阶梯字号，行内居中排列，
 * 紫/粉/灰三色轮换。数据由 [setData] 注入（词, 次数 倒序）。
 */
class WordCloudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var words = listOf<Pair<String, Int>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val colors = intArrayOf(
        0xFF6750A4.toInt(), 0xFFF48FB1.toInt(), 0xFF999999.toInt()
    )

    fun setData(data: List<Pair<String, Int>>) {
        words = data
        contentDescription = if (data.isEmpty()) {
            "标题高频词：暂无数据"
        } else {
            "标题高频词：" + data.joinToString("，") { (word, count) -> "$word $count 次" }
        }
        invalidate()
    }

    /** 按内容换行后的真实高度（px）：供海报渲染器动态预留空间，不再裁断 */
    fun computeContentHeight(widthPx: Int): Int {
        if (words.isEmpty()) return 0
        val rows = layoutRows(widthPx.toFloat())
        var h = dp(24)
        rows.forEach { row -> h += (row.maxOf { it.second }.toInt()) + dp(10) }
        return h + dp(8)
    }

    // 行布局：(词, 字号px) 逐行；onDraw 与 computeContentHeight 共用同一套
    private fun layoutRows(maxWidth: Float): List<List<Pair<String, Float>>> {
        val maxCount = words.first().second.coerceAtLeast(1)
        val padX = dp(12).toFloat()
        val wordGap = dp(16).toFloat()
        val rows = mutableListOf<MutableList<Pair<String, Float>>>()
        var current = mutableListOf<Pair<String, Float>>()
        var x = padX
        words.forEach { (word, count) ->
            // 字号阶梯（压缩版）：最大 24sp，最小 12sp（海报空间有限）
            val size = (12 + 12f * count / maxCount) * resources.displayMetrics.scaledDensity
            paint.textSize = size
            val w = paint.measureText(word)
            if (x + w > maxWidth - padX && current.isNotEmpty()) {
                rows += current
                current = mutableListOf()
                x = padX
            }
            current += word to size
            x += w + wordGap
        }
        if (current.isNotEmpty()) rows += current
        return rows
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (words.isEmpty()) return
        val rows = layoutRows(width.toFloat())
        val padX = dp(12).toFloat()
        val lineGap = dp(10).toFloat()
        val wordGap = dp(16).toFloat()
        var y = dp(24).toFloat()
        var index = 0
        rows.forEach { row ->
            val lineHeight = row.maxOf { it.second }
            var x = padX
            row.forEach { (word, size) ->
                paint.textSize = size
                paint.color = colors[index % colors.size]
                val w = paint.measureText(word)
                canvas.drawText(word, x + w / 2, y + size * 0.8f, paint)
                x += w + wordGap
                index++
            }
            y += lineHeight + lineGap
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
