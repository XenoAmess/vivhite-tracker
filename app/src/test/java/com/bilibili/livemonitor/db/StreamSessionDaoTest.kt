package com.bilibili.livemonitor.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `空库边界 全部空结果`() = runBlocking {
        assertEquals(null, dao.findOpenSession())
        assertEquals(0, dao.closedSessionsSince(0).size)
        assertEquals(0, dao.recentSessions(10).size)
        assertEquals(0, dao.titleChanges(999).size)
    }

    @Test
    fun `多场次下 closeOpenSessions 只闭合残留开着的`() = runBlocking {
        dao.insertSession(StreamSessionEntity(startTs = 1000, endTs = 2000)) // 已闭合
        dao.insertSession(StreamSessionEntity(startTs = 3000))               // 开着的
        dao.closeOpenSessions(4000)
        val closed = dao.closedSessionsSince(0)
        assertEquals("闭合 2 场", 2, closed.size)
        assertTrue("残留场次被补闭合", closed.any { it.startTs == 3000L && it.endTs == 4000L })
        assertEquals(null, dao.findOpenSession())
    }

    @Test
    fun `标题变化按 session 隔离且时间升序`() = runBlocking {
        val a = dao.insertSession(StreamSessionEntity(startTs = 1000))
        val b = dao.insertSession(StreamSessionEntity(startTs = 2000))
        dao.insertTitleChange(StreamTitleChangeEntity(sessionId = a, changedAt = 1500, newTitle = "b2"))
        dao.insertTitleChange(StreamTitleChangeEntity(sessionId = a, changedAt = 1200, newTitle = "b1"))
        dao.insertTitleChange(StreamTitleChangeEntity(sessionId = b, changedAt = 2100, newTitle = "b场"))
        val changesA = dao.titleChanges(a)
        assertEquals(listOf("b1", "b2"), changesA.map { it.newTitle })
        assertEquals(listOf("b场"), dao.titleChanges(b).map { it.newTitle })
    }

    @Test
    fun `人气点按 session 隔离且时间升序`() = runBlocking {
        val a = dao.insertSession(StreamSessionEntity(startTs = 1000))
        val b = dao.insertSession(StreamSessionEntity(startTs = 2000))
        dao.insertPopularityPoint(PopularityPointEntity(sessionId = a, ts = 1200, online = 80))
        dao.insertPopularityPoint(PopularityPointEntity(sessionId = a, ts = 1100, online = 50))
        dao.insertPopularityPoint(PopularityPointEntity(sessionId = b, ts = 2100, online = 200))
        assertEquals(listOf(50, 80), dao.popularityPoints(a).map { it.online })
        assertEquals(listOf(200), dao.popularityPoints(b).map { it.online })
        dao.deleteAllPopularityPoints()
        assertEquals(0, dao.popularityPoints(a).size)
    }

    @Test
    fun `粉丝快照升序与最近时间`() = runBlocking {
        dao.insertFollowerSnapshot(FollowerSnapshotEntity(ts = 2000, followerNum = 22420))
        dao.insertFollowerSnapshot(FollowerSnapshotEntity(ts = 1000, followerNum = 22300))
        assertEquals(listOf(22300L, 22420L), dao.followerSnapshots().map { it.followerNum })
        assertEquals(2000L, dao.lastFollowerSnapshotTs())
        dao.deleteAllFollowerSnapshots()
        assertEquals(null, dao.lastFollowerSnapshotTs())
    }
}
