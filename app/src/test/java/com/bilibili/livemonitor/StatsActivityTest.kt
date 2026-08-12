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

    @Test
    fun `心情事件时长 0 不显示结束时间 大于 0 显示起止`() = runBlocking {
        val dao = AppDatabase.get(context).moodEventDao()
        dao.insert(
            com.bilibili.livemonitor.db.MoodEventEntity(
                eventTs = todayStart() + 10 * 3_600_000L, durationMin = 90,
                mood = "happy", title = "有时长", createdAt = System.currentTimeMillis()
            )
        )
        dao.insert(
            com.bilibili.livemonitor.db.MoodEventEntity(
                eventTs = todayStart() + 12 * 3_600_000L, durationMin = 0,
                mood = "calm", title = "没时长", createdAt = System.currentTimeMillis()
            )
        )
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("mood list") {
            activity.findViewById<RecyclerView>(R.id.rvMoodEvents).adapter!!.itemCount == 2
        }
        val rv = activity.findViewById<RecyclerView>(R.id.rvMoodEvents)
        val text0 = rv.findViewHolderForAdapterPosition(0)!!.itemView
            .findViewById<TextView>(R.id.tvMoodEventTitle).text.toString()
        val text1 = rv.findViewHolderForAdapterPosition(1)!!.itemView
            .findViewById<TextView>(R.id.tvMoodEventTitle).text.toString()
        assertTrue(text0, text0.startsWith("10:00 ~ 11:30"))
        assertTrue(text1, text1.startsWith("12:00"))
        assertTrue(text1, !text1.contains("~"))
    }

    @Test
    fun `心情编辑对话框 改动时长联动结束时间`() = runBlocking {
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("calendar rendered") {
            activity.findViewById<android.widget.GridLayout>(R.id.calendarGrid).childCount > 7
        }
        activity.findViewById<android.view.View>(R.id.btnAddMoodEvent).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        val btnTime = dialog.findViewById<TextView>(R.id.btnMoodEventTime)!!
        val btnEnd = dialog.findViewById<TextView>(R.id.btnMoodEventEnd)!!
        val etDuration = dialog.findViewById<android.widget.EditText>(R.id.etMoodEventDuration)!!

        // 初始：时长 0 → 不展示结束时间
        assertEquals("结束：--", btnEnd.text.toString())

        etDuration.setText("45")
        fun minutesOf(buttonText: String): Int {
            val hm = buttonText.substringAfter("：")
            val h = hm.substringBefore(":").toInt()
            val m = hm.substringAfter(":").toInt()
            return h * 60 + m
        }
        val startMin = minutesOf(btnTime.text.toString())
        val endMin = minutesOf(btnEnd.text.toString())
        assertEquals("结束时间应 = 开始 + 45 分钟", 45, (endMin - startMin + 24 * 60) % (24 * 60))

        // 清空时长 → 回到不展示
        etDuration.setText("")
        assertEquals("结束：--", btnEnd.text.toString())
    }

    // ---------- 备份导入 ----------

    @Test
    fun `选中魔法期日 提示含第几天`() = runBlocking {
        // 今天起 3 天魔法期
        val start = todayStart()
        com.bilibili.livemonitor.util.PreferenceManager(context).setMagicPeriodsJson(
            org.json.JSONArray().put(
                org.json.JSONObject().put("start", start).put("end", start + 3 * 86_400_000L)
            ).toString()
        )
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("magic hint") {
            activity.findViewById<TextView>(R.id.tvSelectedDayHint).text.toString().contains("魔法期第 1 天")
        }
        // 清理，避免污染同测试类其他用例
        com.bilibili.livemonitor.util.PreferenceManager(context).setMagicPeriodsJson("[]")
    }

    @Test
    fun `导入CSV 合并去重 场次与心情`() = runBlocking {
        val sdao = AppDatabase.get(context).streamSessionDao()
        val mdao = AppDatabase.get(context).moodEventDao()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        fun ts(s: String) = fmt.parse(s)!!.time

        // 预置：1 场次 + 1 心情（与 CSV 中重复）
        sdao.insertSession(StreamSessionEntity(startTs = ts("2026-08-09 20:27"), endTs = ts("2026-08-09 23:01"), title = "sad"))
        mdao.insert(
            com.bilibili.livemonitor.db.MoodEventEntity(
                eventTs = ts("2026-08-09 21:00"), durationMin = 0, mood = "happy",
                title = "看了场直播", createdAt = 0
            )
        )

        val csv = com.bilibili.livemonitor.domain.SessionBackup.HEADER + "\n" +
            "场次,2026-08-09 20:27,2026-08-09 23:01,154,\"sad\",,,\n" +          // 重复
            "场次,2026-08-08 20:00,2026-08-08 22:00,120,\"新场次\",,,\n" +          // 新增
            "心情,2026-08-09 21:00,,0,\"看了场直播\",😄开心,,\n" +                   // 重复（display 反查）
            "心情,2026-08-08 21:00,,0,\"新心情\",😢难过,,\"备注\"\n" +                 // 新增
            "场次,坏行,,,,\"x\",,,\n"                                            // 无法解析

        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        waitFor("summary") {
            activity.findViewById<TextView>(R.id.tvStatsSummary).text.toString().contains("本周")
        }
        val result = activity.importCsvText(csv)
        assertEquals(1, result.sessionsAdded)
        assertEquals(1, result.sessionsSkipped)
        assertEquals(1, result.moodsAdded)
        assertEquals(1, result.moodsSkipped)
        assertEquals(1, result.badLines)

        // DB 终态：2 场次 + 2 心情；心情 display 已反查为 key
        assertEquals(2, sdao.closedSessionsSince(0).size)
        val allMoods = mdao.all()
        assertEquals(2, allMoods.size)
        assertTrue(allMoods.any { it.mood == "sad" && it.note == "备注" })
    }
}
