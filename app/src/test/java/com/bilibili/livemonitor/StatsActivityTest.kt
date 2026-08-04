package com.bilibili.livemonitor

import android.content.Context
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StatsActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // 每个用例清空场次（Robolectric 同一测试类共享 filesDir/DB）
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
        }
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout: $what")
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    @Test
    fun `空库渲染零统计`() {
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("summary") {
            activity.findViewById<TextView>(R.id.tvStatsSummary).text.toString().contains("本周")
        }
        assertTrue(activity.findViewById<TextView>(R.id.tvStatsSummary).text.toString().contains("本周 0 场"))
    }

    @Test
    fun `有场次时统计与列表渲染`() = runBlocking {
        val dao = AppDatabase.get(context).streamSessionDao()
        val now = System.currentTimeMillis()
        dao.insertSession(StreamSessionEntity(startTs = now - 3_600_000, endTs = now, title = "测试直播"))

        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("summary") {
            activity.findViewById<TextView>(R.id.tvStatsSummary).text.toString().contains("本周 1 场")
        }
        val text = activity.findViewById<TextView>(R.id.tvStatsSummary).text.toString()
        assertTrue(text, text.contains("本周 1 场"))
        val rv = activity.findViewById<RecyclerView>(R.id.rvSessions)
        assertEquals(1, rv.adapter!!.itemCount)
    }
}
