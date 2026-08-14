package com.bilibili.livemonitor

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 绮迹手账 UI 自动化（真机/模拟器，Espresso）：心情增删改全链路 +
 * 备份导出文件内容。instrumented 方法名不能用反引号含空格（DEX 限制）。
 */
@RunWith(AndroidJUnit4::class)
class StatsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
            AppDatabase.get(context).moodEventDao().deleteAll()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
            AppDatabase.get(context).moodEventDao().deleteAll()
        }
        File(context.cacheDir, "shared/vivhite_backup.csv").delete()
    }

    @Test
    fun moodAddEditDelete() = runBlocking {
        val dao = AppDatabase.get(context).moodEventDao()
        ActivityScenario.launch(StatsActivity::class.java).use { scenario ->
            // 添加：弹窗 → 选「😄开心」→ 填标题 → 保存
            onView(withId(R.id.btnAddMoodEvent)).perform(click())
            onView(withText("😄开心")).inRoot(isDialog()).perform(click())
            onView(withId(R.id.etMoodEventTitle)).inRoot(isDialog())
                .perform(replaceText("instrumented 添加"))
            onView(withText("保存")).inRoot(isDialog()).perform(click())
            waitForDao("add") { runBlocking { dao.all().isNotEmpty() } }
            val added = dao.all().first()
            assertEquals("instrumented 添加", added.title)
            assertEquals("happy", added.mood)

            // 编辑：等列表渲染出条目再点（CI 模拟器慢，点早会找不着）
            waitForMoodList("add visible", scenario, 1)
            onView(withText(containsString("instrumented 添加"))).perform(click())
            onView(withId(R.id.etMoodEventTitle)).inRoot(isDialog())
                .perform(replaceText("instrumented 编辑后"))
            onView(withText("保存")).inRoot(isDialog()).perform(click())
            waitForDao("edit") {
                runBlocking { dao.all().firstOrNull()?.title == "instrumented 编辑后" }
            }

            // 删除：点删除图标 → 确认
            onView(withId(R.id.btnMoodEventDelete)).perform(click())
            onView(withText("删除")).inRoot(isDialog()).perform(click())
            waitForDao("delete") { runBlocking { dao.all().isEmpty() } }
            assertEquals(0, dao.all().size)
        }
        Unit
    }

    @Test
    fun exportWritesCsvWithMoods() = runBlocking {
        val sdao = AppDatabase.get(context).streamSessionDao()
        val mdao = AppDatabase.get(context).moodEventDao()
        sdao.insertSession(
            StreamSessionEntity(startTs = 1_700_000_000_000, endTs = 1_700_003_600_000, title = "导出验证场")
        )
        mdao.insert(
            MoodEventEntity(eventTs = 1_700_001_800_000, mood = "happy", title = "导出验证心情", createdAt = 0)
        )
        ActivityScenario.launch(StatsActivity::class.java).use { scenario ->
            scenario.onActivity { it.exportSessions() }
            val file = File(context.cacheDir, "shared/vivhite_backup.csv")
            val deadline = System.currentTimeMillis() + 10_000
            while ((!file.exists() || file.length() == 0L) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            val text = file.readText(Charsets.UTF_8)
            assertTrue(text.startsWith(com.bilibili.livemonitor.domain.SessionBackup.HEADER))
            assertTrue("场次行: $text", text.contains("导出验证场"))
            assertTrue("心情行含 display: $text", text.contains("😄开心"))
            assertTrue(text.contains("导出验证心情"))
        }
        Unit
    }

    private fun waitForDao(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout: $what")
            Thread.sleep(100)
        }
    }

    /** 等手账页心情列表渲染出条目（DAO 写完后 RecyclerView 刷新有异步延迟） */
    private fun waitForMoodList(what: String, scenario: ActivityScenario<StatsActivity>, count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var current = -1
            scenario.onActivity {
                current = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvMoodEvents)
                    .adapter?.itemCount ?: -1
            }
            if (current == count) return
            Thread.sleep(100)
        }
        throw AssertionError("timeout: $what (mood list count != $count)")
    }

    @Test
    fun moodEditChangeDate() = runBlocking {
        val dao = AppDatabase.get(context).moodEventDao()
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        dao.insert(
            MoodEventEntity(
                eventTs = todayStart + 10 * 3_600_000L + 30 * 60_000L, // 今天 10:30
                mood = "happy", title = "挪日期", createdAt = 0
            )
        )
        ActivityScenario.launch(StatsActivity::class.java).use { scenario ->
            // 等列表渲染出条目再点（CI 模拟器慢，点早会找不着）
            waitForMoodList("mood visible", scenario, 1)
            // 点条目 → 编辑弹窗 → 日期按钮
            onView(withText(containsString("挪日期"))).perform(click())
            onView(withId(R.id.btnMoodEventDate)).inRoot(isDialog()).perform(click())
            // DatePicker 改为昨天（PickerActions month 是 1 基）
            val yesterday = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_MONTH, -1)
            }
            onView(withClassName(org.hamcrest.Matchers.equalTo(android.widget.DatePicker::class.java.name)))
                .inRoot(isDialog())
                .perform(
                    androidx.test.espresso.contrib.PickerActions.setDate(
                        yesterday.get(java.util.Calendar.YEAR),
                        yesterday.get(java.util.Calendar.MONTH) + 1,
                        yesterday.get(java.util.Calendar.DAY_OF_MONTH)
                    )
                )
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            // 保存
            onView(withText("保存")).inRoot(isDialog()).perform(click())
            waitForDao("date moved") {
                runBlocking { dao.eventsBetween(todayStart, todayStart + 86_400_000L).isEmpty() }
            }
            val moved = dao.all().first()
            val movedCal = java.util.Calendar.getInstance().apply { timeInMillis = moved.eventTs }
            assertEquals(
                yesterday.get(java.util.Calendar.DAY_OF_YEAR),
                movedCal.get(java.util.Calendar.DAY_OF_YEAR)
            )
            assertEquals(
                "时分保留",
                10 * 60 + 30,
                movedCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + movedCal.get(java.util.Calendar.MINUTE)
            )
        }
        Unit
    }
}
