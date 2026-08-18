package com.bilibili.livemonitor.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.FollowerSnapshotEntity
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.PopularityPointEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.db.StreamTitleChangeEntity
import com.bilibili.livemonitor.domain.SessionSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = AppDatabase.get(context)
    private val repository = StatsRepository(database)

    @Before
    fun setUp() {
        runBlocking {
            database.streamSessionDao().deleteAllTitleChanges()
            database.streamSessionDao().deleteAllPopularityPoints()
            database.streamSessionDao().deleteAllFollowerSnapshots()
            database.streamSessionDao().deleteAll()
            database.moodEventDao().deleteAll()
        }
    }

    @Test
    fun `month search and trend reads retain dao semantics`() = runBlocking {
        val dao = database.streamSessionDao()
        val sessionId = dao.insertSession(
            StreamSessionEntity(startTs = 100, endTs = 200, title = "月内直播")
        )
        dao.insertSession(StreamSessionEntity(startTs = 300, endTs = null, title = "进行中"))
        dao.insertTitleChange(
            StreamTitleChangeEntity(sessionId = sessionId, changedAt = 150, newTitle = "新主题")
        )
        dao.insertPopularityPoint(
            PopularityPointEntity(sessionId = sessionId, ts = 160, online = 88)
        )
        dao.insertFollowerSnapshot(FollowerSnapshotEntity(ts = 170, followerNum = 22_000))
        database.moodEventDao().insert(
            MoodEventEntity(eventTs = 180, mood = "happy", title = "记录", createdAt = 180)
        )

        assertEquals(listOf(100L), repository.sessionsBetween(50, 250).map { it.startTs })
        val hits = repository.search("开心")
        assertEquals(1, hits.size)
        assertEquals(SessionSearch.Kind.MOOD, hits.single().kind)

        val trend = repository.trendData(since = 50, popularityFrom = 100, popularityTo = 200)
        assertEquals(listOf(100L), trend.sessions.map { it.startTs })
        assertEquals(88, trend.popularity.single().online)
        assertEquals(22_000L, trend.followers.single().followerNum)
        assertTrue(trend.titles.containsAll(listOf("月内直播", "进行中", "新主题")))
    }
}
