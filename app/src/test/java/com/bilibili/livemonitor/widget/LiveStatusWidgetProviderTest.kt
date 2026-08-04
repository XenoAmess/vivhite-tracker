package com.bilibili.livemonitor.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.Assert.assertEquals
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
}
