package com.bilibili.livemonitor.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.PopularityPointEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class StatsImageDataFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDatabase() = runBlocking {
        AppDatabase.get(context).streamSessionDao().deleteAll()
        AppDatabase.get(context).moodEventDao().deleteAll()
        AppDatabase.get(context).mediaSnapshotDao().deleteAll()
    }

    @Test
    fun `月报数据不截断超过五百场的月份`() = runBlocking {
        val month = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = month.timeInMillis
        repeat(520) { index ->
            val sessionStart = start + index * 60_000L
            AppDatabase.get(context).streamSessionDao().insertSession(
                StreamSessionEntity(
                    startTs = sessionStart,
                    endTs = sessionStart + 30_000L,
                    title = "场次 $index"
                )
            )
        }

        val data = StatsImageDataFactory.build(context, month)

        assertEquals(520, data.records.count {
            it.kind == StatsImageRenderer.RecordKind.SESSION
        })
    }

    @Test
    fun `月报保留心情原因备注并为直播组装封面和人气`() = runBlocking {
        val month = monthStart()
        val start = month.timeInMillis + 20 * 60_000L
        val cover = java.io.File(context.filesDir, "covers/poster-cover.jpg").apply {
            parentFile!!.mkdirs()
        }
        val dao = AppDatabase.get(context).streamSessionDao()
        val sessionId = dao.insertSession(
            StreamSessionEntity(
                startTs = start,
                endTs = start + 90 * 60_000L,
                title = "月报测试直播",
                coverPath = cover.absolutePath
            )
        )
        dao.insertPopularityPoint(
            PopularityPointEntity(sessionId = sessionId, ts = start + 10_000L, online = 120)
        )
        dao.insertPopularityPoint(
            PopularityPointEntity(sessionId = sessionId, ts = start + 70_000L, online = 260)
        )
        AppDatabase.get(context).moodEventDao().insert(
            MoodEventEntity(
                eventTs = start + 30_000L,
                durationMin = 45,
                mood = "happy",
                title = "看直播",
                reason = "唱了喜欢的歌",
                note = "以后还想再听",
                createdAt = start
            )
        )

        val data = StatsImageDataFactory.build(context, month)
        val session = data.records.first { it.kind == StatsImageRenderer.RecordKind.SESSION }
        val mood = data.records.first { it.kind == StatsImageRenderer.RecordKind.MOOD }

        assertEquals(listOf(start + 10_000L to 120, start + 70_000L to 260), session.popularityPoints)
        assertEquals(listOf(cover.absolutePath), session.coverPaths)
        assertEquals(listOf("原因：唱了喜欢的歌", "备注：以后还想再听"), mood.detailLines)
        assertTrue(mood.text.contains("45 分钟"))
        assertTrue(data.summaryLines.first().contains("共 1小时30分"))
        assertTrue(data.summaryLines.first().contains("活跃 1 天"))
    }

    @Test
    fun `月底开播场次包含次月的人气采样`() = runBlocking {
        val month = monthStart()
        val nextMonth = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        val start = nextMonth - 30 * 60_000L
        val dao = AppDatabase.get(context).streamSessionDao()
        val sessionId = dao.insertSession(
            StreamSessionEntity(startTs = start, endTs = nextMonth + 90 * 60_000L, title = "跨月直播")
        )
        dao.insertPopularityPoint(
            PopularityPointEntity(sessionId = sessionId, ts = start + 10_000L, online = 100)
        )
        dao.insertPopularityPoint(
            PopularityPointEntity(sessionId = sessionId, ts = nextMonth + 30_000L, online = 200)
        )

        val session = StatsImageDataFactory.build(context, month).records.single {
            it.kind == StatsImageRenderer.RecordKind.SESSION
        }

        assertEquals(listOf(start + 10_000L to 100, nextMonth + 30_000L to 200), session.popularityPoints)
    }

    private fun monthStart(): Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
