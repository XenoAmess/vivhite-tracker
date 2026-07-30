package com.bilibili.livemonitor

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.util.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // AppLogger 是单例，logFile 只在 init 时绑定一次且跨 Robolectric 沙箱残留
        // 旧沙箱路径（导出功能 FileProvider 会拿它对比当前沙箱 roots 导致不匹配），
        // 每个用例前重置让其重新绑定到当前沙箱
        resetAppLoggerFile()
        // FileProvider.sCache 同样跨沙箱残留（先跑者的 roots 绑到旧 filesDir）
        com.bilibili.livemonitor.util.FileProviderTestUtil.clearFileProviderCache()
        AppLogger.init(context)
        AppLogger.clear()
        waitFor { AppLogger.readAll().isEmpty() }
    }

    private fun resetAppLoggerFile() {
        val field = AppLogger::class.java.getDeclaredField("logFile")
        field.isAccessible = true
        field.set(null, null)
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

    @Test
    fun `日志超过2000行时 只显示尾部并提示`() {
        // 真机崩溃根因：全量 ~1MB 塞进 TextView 排版卡死。必须只读尾部
        AppLogger.d("TestTag", "HEAD_MARKER_SHOULD_BE_TRIMMED")
        repeat(2100) { AppLogger.d("T", "padding-line-$it") }
        AppLogger.d("TestTag", "TAIL_MARKER_SHOULD_SHOW")
        waitFor(timeoutMillis = 30_000) { AppLogger.readAll().contains("TAIL_MARKER_SHOULD_SHOW") }

        val activity = Robolectric.buildActivity(LogActivity::class.java).create().get()

        val text = activity.findViewById<TextView>(R.id.tvLog).text.toString()
        assertTrue("应有截断提示: ${text.take(100)}", text.contains("仅显示最近"))
        assertTrue("尾部标记应显示", text.contains("TAIL_MARKER_SHOULD_SHOW"))
        assertFalse("头部标记应被截掉", text.contains("HEAD_MARKER_SHOULD_BE_TRIMMED"))
        assertTrue("显示长度应受控", text.length < 200_000)
    }

    @Test
    fun `点导出 发出带FileProvider链接的分享intent`() {
        AppLogger.d("TestTag", "export-marker")
        waitFor { AppLogger.readAll().contains("export-marker") }
        val activity = Robolectric.buildActivity(LogActivity::class.java).create().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnExport
        ).performClick()
        val started = shadowOf(context).nextStartedActivity
        // startActivity(createChooser(...))：外层是 CHOOSER，内嵌 ACTION_SEND
        assertTrue(started != null)
        val sendIntent = started.getParcelableExtra<android.content.Intent>(Intent.EXTRA_INTENT)
        if (started.action == Intent.ACTION_CHOOSER && sendIntent != null) {
            assertEquals(Intent.ACTION_SEND, sendIntent.action)
            val uri = sendIntent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            assertTrue("应带 FileProvider 链接: $uri", uri.toString().contains("com.bilibili.livemonitor.fileprovider"))
        } else {
            assertEquals(Intent.ACTION_SEND, started.action)
        }
    }
}
