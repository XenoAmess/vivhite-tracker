package com.bilibili.livemonitor

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 绮迹手账 UI 自动化（真机/模拟器）：心情增删改全链路 + 备份导出文件内容。
 *
 * 交互策略（CI 模拟器踩坑实录）：
 * - 列表条目点击走 ViewHolder 直点（Espresso 的 90% 可见度约束/scrollTo 在
 *   ScrollView 屏外条目上必败）
 * - 对话框交互走 UiAutomator（Espresso 的 RootViewWithoutFocus 等待在慢
 *   模拟器上 10s 超时；UiDevice 不挑窗口焦点）
 *
 * instrumented 方法名不能用反引号含空格（DEX 限制）。
 */
@RunWith(AndroidJUnit4::class)
class StatsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device: UiDevice by lazy {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

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

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout: $what")
            Thread.sleep(100)
        }
    }

    private fun waitForMoodList(scenario: ActivityScenario<StatsActivity>, count: Int) {
        waitFor("mood list count=$count") {
            var current = -1
            scenario.onActivity {
                current = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvMoodEvents)
                    .adapter?.itemCount ?: -1
            }
            current == count
        }
    }

    private fun clickMoodItem(scenario: ActivityScenario<StatsActivity>) {
        scenario.onActivity {
            it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvMoodEvents)
                .findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        }
    }

    private fun uiClickText(text: String, timeoutMs: Long = 8_000) {
        val obj = device.wait(
            androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(text)),
            timeoutMs
        ) ?: throw AssertionError("UI 元素未出现: $text")
        obj.click()
    }

    @Test
    fun moodAddEditDelete() = runBlocking {
        val dao = AppDatabase.get(context).moodEventDao()
        ActivityScenario.launch(StatsActivity::class.java).use { scenario ->
            // 添加：弹窗 → 选「😄开心」→ 填标题 → 保存
            scenario.onActivity {
                it.findViewById<android.view.View>(R.id.btnAddMoodEvent).performClick()
            }
            uiClickText("😄开心")
            device.findObject(
                UiSelector().resourceId("com.bilibili.livemonitor:id/etMoodEventTitle")
            ).setText("instrumented 添加")
            uiClickText("保存")
            waitFor("add") { runBlocking { dao.all().isNotEmpty() } }
            val added = dao.all().first()
            assertEquals("instrumented 添加", added.title)
            assertEquals("happy", added.mood)

            // 编辑：点条目 → 改标题 → 保存
            waitForMoodList(scenario, 1)
            clickMoodItem(scenario)
            device.findObject(
                UiSelector().resourceId("com.bilibili.livemonitor:id/etMoodEventTitle")
            ).setText("instrumented 编辑后")
            uiClickText("保存")
            waitFor("edit") {
                runBlocking { dao.all().firstOrNull()?.title == "instrumented 编辑后" }
            }

            // 删除：点删除图标 → 确认
            scenario.onActivity {
                it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvMoodEvents)
                    .findViewHolderForAdapterPosition(0)!!.itemView
                    .findViewById<android.view.View>(R.id.btnMoodEventDelete).performClick()
            }
            uiClickText("删除")
            waitFor("delete") { runBlocking { dao.all().isEmpty() } }
            assertEquals(0, dao.all().size)
        }
        Unit
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
            waitForMoodList(scenario, 1)
            clickMoodItem(scenario)
            // 日期按钮 → DatePicker 改昨天 → 确定
            uiClickTextStartsWith("日期：")
            val yesterday = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_MONTH, -1)
            }
            device.wait(
                androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.clazz(android.widget.DatePicker::class.java.name)
                ), 8_000
            )
            androidx.test.espresso.Espresso.onView(
                org.hamcrest.Matchers.instanceOf(android.widget.DatePicker::class.java)
            ).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(
                    androidx.test.espresso.contrib.PickerActions.setDate(
                        yesterday.get(java.util.Calendar.YEAR),
                        yesterday.get(java.util.Calendar.MONTH) + 1,
                        yesterday.get(java.util.Calendar.DAY_OF_MONTH)
                    )
                )
            device.findObject(
                UiSelector().resourceId("android:id/button1")
            ).click()
            uiClickText("保存")
            waitFor("date moved") {
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

    // UiSelector 没有 startsWith，用正则匹配
    private fun uiClickTextStartsWith(prefix: String) {
        val obj = device.wait(
            androidx.test.uiautomator.Until.findObject(
                androidx.test.uiautomator.By.textStartsWith(prefix)
            ),
            8_000
        ) ?: throw AssertionError("UI 元素未出现: $prefix*")
        obj.click()
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
}
