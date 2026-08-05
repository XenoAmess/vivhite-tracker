package com.bilibili.livemonitor.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LiveStatusWidgetProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
    }

    @Test
    fun `buildStatus 三种状态`() {
        assertEquals(R.drawable.img_on to "🔴 直播中", LiveStatusWidgetProvider.buildStatus(monitoring = true, live = true))
        assertEquals(R.drawable.img_off to "未开播", LiveStatusWidgetProvider.buildStatus(monitoring = true, live = false))
        assertEquals(R.drawable.img_off to "已停止监控", LiveStatusWidgetProvider.buildStatus(monitoring = false, live = false))
    }

    @Test
    fun `computeContent 直播中展示标题 否则隐藏`() {
        val live = LiveStatusWidgetProvider.computeContent(monitoring = true, live = true, lastLiveTitle = "今晚播点什么")
        assertTrue(live.showLiveTitle)
        assertEquals("今晚播点什么", live.liveTitle)
        assertEquals(R.drawable.img_on, live.iconRes)

        val liveBlank = LiveStatusWidgetProvider.computeContent(monitoring = true, live = true, lastLiveTitle = "")
        assertFalse("标题为空不应展示", liveBlank.showLiveTitle)

        val notLive = LiveStatusWidgetProvider.computeContent(monitoring = true, live = false, lastLiveTitle = "残留标题")
        assertFalse("未开播不展示标题", notLive.showLiveTitle)

        val stopped = LiveStatusWidgetProvider.computeContent(monitoring = false, live = false, lastLiveTitle = "残留标题")
        assertFalse("停止监控不展示标题", stopped.showLiveTitle)
        assertEquals(R.drawable.img_off, stopped.iconRes)
        assertEquals("已停止监控", stopped.statusText)
    }
}
