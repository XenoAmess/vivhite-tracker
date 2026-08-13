package com.bilibili.livemonitor.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// 像素级断言需要真实栅格化：LEGACY 模式下 Canvas 只记录不渲染
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PopularityChartViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `两点以上画折线 有墨迹`() {
        val width = 700
        val height = 400
        val view = PopularityChartView(context)
        val base = 1_700_000_000_000L
        view.setData(listOf(base to 50, base + 60_000 to 80, base + 120_000 to 65))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        var ink = 0
        for (py in 0 until height) for (px in 0 until width) {
            if (android.graphics.Color.alpha(bmp.getPixel(px, py)) > 0) ink++
        }
        assertTrue("折线与文字应产生墨迹", ink > 0)
    }
}
