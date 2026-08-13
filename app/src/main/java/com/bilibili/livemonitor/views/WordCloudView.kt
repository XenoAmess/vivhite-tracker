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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (words.isEmpty()) return
        val maxCount = words.first().second.coerceAtLeast(1)
        val padX = dp(12).toFloat()
        val lineGap = dp(10).toFloat()
        val wordGap = dp(16).toFloat()

        var x = padX
        var y = dp(24).toFloat()
        var lineHeight = 0f
        words.forEachIndexed { i, (word, count) ->
            // 字号阶梯：最大词 34sp，最小 16sp
            val size = (16 + 18f * count / maxCount) * resources.displayMetrics.scaledDensity
            paint.textSize = size
            paint.color = colors[i % colors.size]
            val w = paint.measureText(word)
            if (x + w > width - padX && x > padX) {
                // 换行
                x = padX
                y += lineHeight + lineGap
                lineHeight = 0f
            }
            canvas.drawText(word, x + w / 2, y + size * 0.8f, paint)
            x += w + wordGap
            lineHeight = maxOf(lineHeight, size)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
