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
    fun `resolveState 区分停止在播未播和异常过期`() {
        val now = 1_000_000L
        assertEquals(
            LiveStatusWidgetProvider.WidgetState.STOPPED,
            LiveStatusWidgetProvider.resolveState(false, now, true, true, now)
        )
        assertEquals(
            LiveStatusWidgetProvider.WidgetState.LIVE,
            LiveStatusWidgetProvider.resolveState(true, now, true, true, now)
        )
        assertEquals(
            LiveStatusWidgetProvider.WidgetState.NOT_LIVE,
            LiveStatusWidgetProvider.resolveState(true, now, true, false, now)
        )
        assertEquals(
            LiveStatusWidgetProvider.WidgetState.ERROR_OR_STALE,
            LiveStatusWidgetProvider.resolveState(true, now, false, true, now)
        )
        assertEquals(
            LiveStatusWidgetProvider.WidgetState.ERROR_OR_STALE,
            LiveStatusWidgetProvider.resolveState(
                true,
                now - LiveStatusWidgetProvider.STATUS_STALE_AFTER - 1,
                true,
                true,
                now
            )
        )
        assertEquals(
            R.drawable.img_off to "监控异常或状态过期",
            LiveStatusWidgetProvider.buildStatus(LiveStatusWidgetProvider.WidgetState.ERROR_OR_STALE)
        )
    }

    @Test
    fun `computeContent 直播中展示标题 否则隐藏`() {
        val live = LiveStatusWidgetProvider.computeContent(LiveStatusWidgetProvider.WidgetState.LIVE, "今晚播点什么")
        assertTrue(live.showLiveTitle)
        assertEquals("今晚播点什么", live.liveTitle)
        assertEquals(R.drawable.img_on, live.iconRes)

        val liveBlank = LiveStatusWidgetProvider.computeContent(LiveStatusWidgetProvider.WidgetState.LIVE, "")
        assertFalse("标题为空不应展示", liveBlank.showLiveTitle)

        val notLive = LiveStatusWidgetProvider.computeContent(LiveStatusWidgetProvider.WidgetState.NOT_LIVE, "残留标题")
        assertFalse("未开播不展示标题", notLive.showLiveTitle)

        val stopped = LiveStatusWidgetProvider.computeContent(LiveStatusWidgetProvider.WidgetState.STOPPED, "残留标题")
        assertFalse("停止监控不展示标题", stopped.showLiveTitle)
        assertEquals(R.drawable.img_off, stopped.iconRes)
        assertEquals("已停止监控", stopped.statusText)
    }
}
