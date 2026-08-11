package com.bilibili.livemonitor.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoodEventDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MoodEventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.moodEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(ts: Long, title: String = "t") =
        MoodEventEntity(eventTs = ts, mood = "happy", title = title, createdAt = ts)

    @Test
    fun `插入更新删除 全流程`() = runBlocking {
        val id = dao.insert(event(1000, "开播了"))
        var list = dao.eventsBetween(0, 2000)
        assertEquals(1, list.size)
        assertEquals("开播了", list[0].title)
        assertEquals("默认时长为 0", 0, list[0].durationMin)

        dao.update(list[0].copy(title = "下播了", mood = "sad", reason = "没看够", durationMin = 90))
        list = dao.eventsBetween(0, 2000)
        assertEquals("下播了", list[0].title)
        assertEquals("sad", list[0].mood)
        assertEquals("没看够", list[0].reason)
        assertEquals(90, list[0].durationMin)

        dao.delete(list[0])
        assertEquals(0, dao.eventsBetween(0, 2000).size)
    }

    @Test
    fun `按日查询 半开区间边界`() = runBlocking {
        val dayStart = 86_400_000L
        dao.insert(event(dayStart - 1, "前一日23:59:59"))   // 前一天
        dao.insert(event(dayStart, "当日0点"))             // 当天开始
        dao.insert(event(dayStart + 86_399_000L, "当日23:59")) // 当天末尾
        dao.insert(event(dayStart + 86_400_000L, "次日0点"))   // 次日
        val list = dao.eventsBetween(dayStart, dayStart + 86_400_000L)
        assertEquals(listOf("当日0点", "当日23:59"), list.map { it.title })
    }

    @Test
    fun `当日多条按时间升序`() = runBlocking {
        dao.insert(event(3000, "c"))
        dao.insert(event(1000, "a"))
        dao.insert(event(2000, "b"))
        assertEquals(listOf("a", "b", "c"), dao.eventsBetween(0, 4000).map { it.title })
    }
}
