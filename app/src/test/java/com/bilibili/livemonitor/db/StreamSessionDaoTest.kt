package com.bilibili.livemonitor.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StreamSessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.streamSessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `插入场次可查回 且未闭合`() = runBlocking {
        val id = dao.insertSession(StreamSessionEntity(startTs = 1000, title = "t1"))
        val open = dao.findOpenSession()
        assertNotNull(open)
        assertEquals(id, open!!.id)
        assertEquals("t1", open.title)
        assertEquals(null, open.endTs)
    }

    @Test
    fun `闭合场次 统计窗口内可见`() = runBlocking {
        dao.insertSession(StreamSessionEntity(startTs = 1000))
        dao.closeOpenSessions(2000)
        val sessions = dao.closedSessionsSince(0)
        assertEquals(1, sessions.size)
        assertEquals(2000L, sessions[0].endTs)
        assertEquals(null, dao.findOpenSession())
    }

    @Test
    fun `最近场次按下播倒序`() = runBlocking {
        dao.insertSession(StreamSessionEntity(startTs = 3000, endTs = 4000))
        dao.insertSession(StreamSessionEntity(startTs = 1000, endTs = 2000))
        val recent = dao.recentSessions(10)
        assertEquals(listOf(3000L, 1000L), recent.map { it.startTs })
    }

    @Test
    fun `标题变化记录挂到场次`() = runBlocking {
        val sid = dao.insertSession(StreamSessionEntity(startTs = 1000))
        dao.insertTitleChange(
            StreamTitleChangeEntity(sessionId = sid, changedAt = 1500, oldTitle = "a", newTitle = "b")
        )
        val changes = dao.titleChanges(sid)
        assertEquals(1, changes.size)
        assertEquals("b", changes[0].newTitle)
    }
}
