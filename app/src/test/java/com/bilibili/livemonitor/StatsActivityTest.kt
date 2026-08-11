package com.bilibili.livemonitor

import android.content.Context
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // 每个用例清空场次与心情事件（Robolectric 同一测试类共享 filesDir/DB）
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
            AppDatabase.get(context).moodEventDao().deleteAll()
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

    @Test
    fun `点选无场次日期 列表清空提示无直播`() = runBlocking {
        val dao = AppDatabase.get(context).streamSessionDao()
        val now = System.currentTimeMillis()
        // 今天早上 9 点一场
        val todayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        dao.insertSession(StreamSessionEntity(startTs = todayStart + 9 * 3_600_000, endTs = todayStart + 11 * 3_600_000))

        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("calendar rendered") {
            activity.findViewById<android.widget.GridLayout>(R.id.calendarGrid).childCount > 7
        }
        // 点选明天（今天+1 天，无场次）
        val tomorrowCal = java.util.Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val targetDay = tomorrowCal.get(java.util.Calendar.DAY_OF_MONTH)
        val grid = activity.findViewById<android.widget.GridLayout>(R.id.calendarGrid)
        val dayCell = (0 until grid.childCount).map { grid.getChildAt(it) as TextView }
            .firstOrNull { it.text.toString() == targetDay.toString() && it.text.isNotEmpty() }
        assertNotNull("日历应能找到明天日期格", dayCell)
        dayCell!!.performClick()
        waitFor("no-live hint") {
            activity.findViewById<TextView>(R.id.tvSelectedDayHint).text.toString().contains("无直播")
        }
        assertEquals(0, activity.findViewById<RecyclerView>(R.id.rvSessions).adapter!!.itemCount)
        // 空引导不显示（库里今天有场次）
        assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.tvEmptyGuide).visibility)
    }

    @Test
    fun `选中日展示主题变化时间线`() = runBlocking {
        val dao = AppDatabase.get(context).streamSessionDao()
        val now = System.currentTimeMillis()
        val start = now - 3_600_000
        val sid = dao.insertSession(StreamSessionEntity(startTs = start, endTs = now, title = "开场标题"))
        dao.insertTitleChange(
            com.bilibili.livemonitor.db.StreamTitleChangeEntity(
                sessionId = sid, changedAt = now - 1_800_000, oldTitle = "开场标题", newTitle = "换了个主题"
            )
        )
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("summary") {
            activity.findViewById<TextView>(R.id.tvStatsSummary).text.toString().contains("本周 1 场")
        }
        waitFor("title change line") {
            val tv = activity.findViewById<TextView>(R.id.tvDayTitleChanges)
            tv.visibility == android.view.View.VISIBLE && tv.text.toString().contains("换了个主题")
        }
        assertEquals(
            "本日主题变化",
            true,
            activity.findViewById<TextView>(R.id.tvDayTitleChanges).text.toString().startsWith("本日主题变化")
        )
    }

    // ---------- 心情事件 ----------

    private fun todayStart(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `添加心情事件 列表显示且空态隐藏`() = runBlocking {
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("calendar rendered") {
            activity.findViewById<android.widget.GridLayout>(R.id.calendarGrid).childCount > 7
        }
        activity.findViewById<android.view.View>(R.id.btnAddMoodEvent).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        dialog.findViewById<android.widget.EditText>(R.id.etMoodEventTitle)!!.setText("看了场直播")
        val chipGroup = dialog.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupMood)!!
        (chipGroup.getChildAt(0) as com.google.android.material.chip.Chip).performClick()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        waitFor("mood event added") {
            activity.findViewById<RecyclerView>(R.id.rvMoodEvents).adapter!!.itemCount == 1
        }
        assertEquals(
            android.view.View.GONE,
            activity.findViewById<TextView>(R.id.tvMoodEventsEmpty).visibility
        )
        assertEquals(
            1,
            AppDatabase.get(context).moodEventDao()
                .eventsBetween(todayStart(), todayStart() + 86_400_000L).size
        )
    }

    @Test
    fun `编辑心情事件 字段更新`() = runBlocking {
        val dao = AppDatabase.get(context).moodEventDao()
        dao.insert(
            com.bilibili.livemonitor.db.MoodEventEntity(
                eventTs = todayStart() + 10 * 3_600_000L, mood = "happy",
                title = "原标题", reason = "旧原因", createdAt = System.currentTimeMillis()
            )
        )
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("mood list") {
            activity.findViewById<RecyclerView>(R.id.rvMoodEvents).adapter!!.itemCount == 1
        }
        val rv = activity.findViewById<RecyclerView>(R.id.rvMoodEvents)
        rv.findViewHolderForAdapterPosition(0)!!.itemView.performClick()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        dialog.findViewById<android.widget.EditText>(R.id.etMoodEventTitle)!!.setText("新标题")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        waitFor("mood event updated") {
            runBlocking {
                dao.eventsBetween(todayStart(), todayStart() + 86_400_000L)
                    .firstOrNull()?.title == "新标题"
            }
        }
        val saved = dao.eventsBetween(todayStart(), todayStart() + 86_400_000L).first()
        assertEquals("happy", saved.mood) // 心情沿用未改
        assertEquals("旧原因", saved.reason)
    }

    @Test
    fun `删除心情事件 确认后消失`() = runBlocking {
        val dao = AppDatabase.get(context).moodEventDao()
        dao.insert(
            com.bilibili.livemonitor.db.MoodEventEntity(
                eventTs = todayStart() + 11 * 3_600_000L, mood = "sad",
                title = "要删的", createdAt = System.currentTimeMillis()
            )
        )
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("mood list") {
            activity.findViewById<RecyclerView>(R.id.rvMoodEvents).adapter!!.itemCount == 1
        }
        val rv = activity.findViewById<RecyclerView>(R.id.rvMoodEvents)
        rv.findViewHolderForAdapterPosition(0)!!.itemView
            .findViewById<android.view.View>(R.id.btnMoodEventDelete).performClick()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        waitFor("mood event deleted") {
            activity.findViewById<RecyclerView>(R.id.rvMoodEvents).adapter!!.itemCount == 0
        }
        assertEquals(
            android.view.View.VISIBLE,
            activity.findViewById<TextView>(R.id.tvMoodEventsEmpty).visibility
        )
        assertEquals(0, dao.eventsBetween(todayStart(), todayStart() + 86_400_000L).size)
    }
}
