package com.bilibili.livemonitor

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.util.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * LogActivity 用户场景（P4）。
 * 真机场景：后台出问题时用户打开日志页，复制日志发给开发者排查。
 */
@RunWith(RobolectricTestRunner::class)
class LogActivityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppLogger.init(context)
        AppLogger.clear()
        waitFor { AppLogger.readAll().isEmpty() }
    }

    private fun waitFor(timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout")
            Thread.sleep(50)
        }
    }

    @Test
    fun `打开页面 显示已有日志`() {
        AppLogger.d("TestTag", "log-page-content-marker")
        waitFor { AppLogger.readAll().contains("log-page-content-marker") }

        val activity = Robolectric.buildActivity(LogActivity::class.java).create().get()

        val text = activity.findViewById<TextView>(R.id.tvLog).text.toString()
        assertTrue(text.contains("log-page-content-marker"))
    }

    @Test
    fun `点复制 日志写入剪贴板`() {
        AppLogger.d("TestTag", "clipboard-marker")
        waitFor { AppLogger.readAll().contains("clipboard-marker") }
        val activity = Robolectric.buildActivity(LogActivity::class.java).create().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnCopy
        ).performClick()

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()
        assertTrue("剪贴板应包含日志", clipText?.contains("clipboard-marker") == true)
    }

    @Test
    fun `点清空 页面日志被清空`() {
        AppLogger.d("TestTag", "to-clear-marker")
        waitFor { AppLogger.readAll().contains("to-clear-marker") }
        val activity = Robolectric.buildActivity(LogActivity::class.java).create().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnClear
        ).performClick()
        waitFor { AppLogger.readAll().isEmpty() }

        assertEquals("", activity.findViewById<TextView>(R.id.tvLog).text.toString())
    }

    @Test
    fun `点刷新 重新加载最新日志`() {
        val activity = Robolectric.buildActivity(LogActivity::class.java).create().get()
        AppLogger.d("TestTag", "refresh-marker")
        waitFor { AppLogger.readAll().contains("refresh-marker") }

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnRefresh
        ).performClick()

        val text = activity.findViewById<TextView>(R.id.tvLog).text.toString()
        assertTrue(text.contains("refresh-marker"))
    }
}
