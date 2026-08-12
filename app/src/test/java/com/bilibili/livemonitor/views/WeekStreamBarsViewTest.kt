package com.bilibili.livemonitor.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// 像素级断言需要真实栅格化：LEGACY 模式下 Canvas 只记录不渲染
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class WeekStreamBarsViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `满高柱的场次数画在视图内 不越出顶部`() {
        // 回归：旧实现满高柱的场次数基线 = -2dp（视图外）——页面被父布局裁掉
        // 看不见，海报离屏绘制则溢出污染上方标题区。修复后柱顶预留一层 countH：
        // 柱顶上方区域应同时存在「数字字形墨迹」与「留白背景」——
        // 旧实现该区域被满高柱体完全填满（无留白），新实现柱体下移（有墨迹有留白）。
        val width = 700
        val height = 300
        val view = WeekStreamBarsView(context)
        view.setData(
            listOf(3, 0, 0, 0, 0, 0, 0),
            listOf("一", "二", "三", "四", "五", "六", "日")
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        val density = context.resources.displayMetrics.density
        val countH = (14 * density).toInt()
        val gap = (8 * density).toInt()
        val barW = (width - gap * 8) / 7
        val barCx = gap + barW / 2 // 第一根柱中心
        var ink = 0
        var blank = 0
        for (py in 0 until countH) {
            for (px in (barCx - 12)..(barCx + 12)) {
                if (android.graphics.Color.alpha(bmp.getPixel(px, py)) > 0) ink++ else blank++
            }
        }
        assertTrue("场次数应出现在柱顶上方区域（应有字形墨迹）", ink > 0)
        assertTrue("柱顶上方区域不应被柱体填满（应有留白）", blank > 0)
    }

    @Test
    fun `零场次画矮条 不画场次数`() {
        val width = 700
        val height = 300
        val view = WeekStreamBarsView(context)
        view.setData(
            listOf(0, 0, 0, 0, 0, 0, 0),
            listOf("一", "二", "三", "四", "五", "六", "日")
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        // 全 0 时不应有任何 accent 实心柱（accent 只用于非零柱与场次数）
        val accent = 0xFF6750A4.toInt()
        var accentPixels = 0
        for (py in 0 until height) {
            for (px in 0 until width) {
                if (bmp.getPixel(px, py) == accent) accentPixels++
            }
        }
        assertEquals(0, accentPixels)
    }

    @Test
    fun `柱数随数据 五柱逐周渲染`() {
        // 本月逐周场次：view 解除 7 柱硬编码后按数据长度渲染
        val width = 700
        val height = 300
        val view = WeekStreamBarsView(context)
        view.setData(
            listOf(1, 2, 1, 1, 0),
            listOf("1-7", "8-14", "15-21", "22-28", "29-31")
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        // 有非零柱 → 画面中存在 accent 像素
        val accent = 0xFF6750A4.toInt()
        var found = false
        run {
            for (py in 0 until height) {
                for (px in 0 until width) {
                    if (bmp.getPixel(px, py) == accent) {
                        found = true
                        return@run
                    }
                }
            }
        }
        assertTrue("五柱逐周应正常渲染", found)
    }
}
