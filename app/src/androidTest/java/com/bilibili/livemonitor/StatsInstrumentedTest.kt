package com.bilibili.livemonitor

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
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
        ActivityScenario.launch(StatsActivity::class.java).use {
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

            // 编辑：点条目 → 改标题 → 保存
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
}
