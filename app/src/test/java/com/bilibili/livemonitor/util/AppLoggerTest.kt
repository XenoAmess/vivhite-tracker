package com.bilibili.livemonitor.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 本地日志（B7）。
 * 真机场景：后台问题排查依赖 monitor.log，写入/读取/截断必须可靠。
 * 注意 AppLogger 写入是异步单线程，断言前需轮询等待落盘。
 */
@RunWith(RobolectricTestRunner::class)
class AppLoggerTest {

    companion object {
        private fun waitFor(what: String, timeoutMillis: Long = 10_000L, cond: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!cond()) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("timeout waiting for: $what")
                }
                Thread.sleep(50)
            }
        }
    }

    @Before
    fun clearLog() {
        // init 放在 @Before：@BeforeClass 阶段 Robolectric 尚未注册 instrumentation
        AppLogger.init(ApplicationProvider.getApplicationContext())
        AppLogger.clear()
        waitFor("clear before test") { AppLogger.readAll().isEmpty() }
    }

    @Test
    fun `写入的日志可以读回`() {
        AppLogger.d("TestTag", "hello-monitor")
        waitFor("log written") { AppLogger.readAll().contains("hello-monitor") }
        val content = AppLogger.readAll()
        assertTrue(content.contains("D/TestTag: hello-monitor"))
    }

    @Test
    fun `异常堆栈也被记录`() {
        AppLogger.e("TestTag", "with-error", RuntimeException("boom"))
        waitFor("error written") { AppLogger.readAll().contains("boom") }
        assertTrue(AppLogger.readAll().contains("RuntimeException"))
    }

    @Test
    fun `清空后无内容`() {
        AppLogger.d("TestTag", "to-be-cleared")
        waitFor("written") { AppLogger.readAll().isNotEmpty() }
        AppLogger.clear()
        waitFor("cleared") { AppLogger.readAll().isEmpty() }
        assertEquals("", AppLogger.readAll())
    }

    @Test
    fun `超过1MB触发截断且保留尾部`() {
        // 每条约 60 字节，写 24k 条（~1.4MB）触发截断
        val marker = "TAIL_MARKER_SHOULD_SURVIVE"
        repeat(24_000) { AppLogger.d("T", "padding-line-$it-padding-padding") }
        AppLogger.d("T", marker)
        // 标记行是最后一次写入，它出现说明队列已全部落盘（此后不会再有截断）
        waitFor("all writes flushed", timeoutMillis = 60_000L) {
            AppLogger.readAll().contains(marker)
        }
        val content = AppLogger.readAll()
        assertTrue("截断标记应存在", content.contains("log trimmed"))
        assertTrue("截断后体积应小于上限", content.length < 1_100_000)
    }
}
