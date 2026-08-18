package com.bilibili.livemonitor.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * StreamSessionTracker（从 LiveCheckService 拆出的场次/主题追踪）决策层测试。
 */
@RunWith(RobolectricTestRunner::class)
class StreamSessionTrackerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamEndDuration: Long? = null
    private lateinit var tracker: StreamSessionTracker

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
        streamEndDuration = null
        tracker = StreamSessionTracker(
            context, prefs, scope,
            onStreamEnd = { streamEndDuration = it },
            onTitleChange = {}
        )
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
            AppDatabase.get(context).streamSessionDao().deleteAllPopularityPoints()
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
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
    fun `parseLiveStartTime 秒 毫秒 日期字符串 非法`() {
        assertEquals(1_000L, tracker.parseLiveStartTime("1"))              // 秒 → ms
        assertEquals(100_000L, tracker.parseLiveStartTime("100"))          // 秒 → ms
        assertEquals(1_600_000_000_000L, tracker.parseLiveStartTime("1600000000000")) // 已是毫秒
        val dateExpected = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .parse("2023-11-14 22:13:20")!!.time
        assertEquals(dateExpected, tracker.parseLiveStartTime("2023-11-14 22:13:20"))
        assertNull(tracker.parseLiveStartTime(null))
        assertNull(tracker.parseLiveStartTime(""))
        assertNull(tracker.parseLiveStartTime("abc"))
    }

    @Test
    fun `关闭标题通知仍记录标题变化`() {
        val sessionId = runBlocking {
            AppDatabase.get(context).streamSessionDao().insertSession(
                StreamSessionEntity(startTs = System.currentTimeMillis() - 60_000)
            )
        }
        prefs.setNotifyTitleChange(false)
        prefs.setLastLiveTitle("旧标题")

        tracker.trackTitleChange("新标题")

        waitFor("title change persisted") {
            runBlocking { AppDatabase.get(context).streamSessionDao().titleChanges(sessionId).size == 1 }
        }
        val change = runBlocking {
            AppDatabase.get(context).streamSessionDao().titleChanges(sessionId).single()
        }
        assertEquals("旧标题", change.oldTitle)
        assertEquals("新标题", change.newTitle)
    }

    @Test
    fun `新场次标题初始化基线不串联上一场`() {
        prefs.setLastLiveTitle("上一场")
        tracker.recordStreamStart("1700000000", "本场标题")
        tracker.trackTitleChange("本场标题")

        waitFor("session inserted") {
            runBlocking { AppDatabase.get(context).streamSessionDao().findOpenSession() != null }
        }
        val open = runBlocking { AppDatabase.get(context).streamSessionDao().findOpenSession()!! }
        assertEquals("本场标题", prefs.getLastLiveTitle())
        assertTrue(runBlocking {
            AppDatabase.get(context).streamSessionDao().titleChanges(open.id).isEmpty()
        })
    }

    @Test
    fun `recordStreamEnd 闭合开场次并回调时长`() {
        val now = System.currentTimeMillis()
        runBlocking {
            AppDatabase.get(context).streamSessionDao()
                .insertSession(StreamSessionEntity(startTs = now - 3_600_000))
        }
        prefs.setNotifyStreamEnd(true)
        tracker.recordStreamEnd()
        waitFor("stream end callback") { streamEndDuration != null }
        assertTrue(
            "时长应接近 1 小时，实际 ${streamEndDuration}",
            streamEndDuration!! in 3_500_000..3_700_000
        )
        // 场次已闭合
        val sessions = runBlocking {
            AppDatabase.get(context).streamSessionDao().recentSessions(5)
        }
        assertEquals(1, sessions.size)
        assertNotNull("下播后应闭合", sessions[0].endTs)
    }

    @Test
    fun `recordStreamEnd 关闭下播提醒时不回调`() {
        val now = System.currentTimeMillis()
        runBlocking {
            AppDatabase.get(context).streamSessionDao()
                .insertSession(StreamSessionEntity(startTs = now - 3_600_000))
        }
        prefs.setNotifyStreamEnd(false)
        tracker.recordStreamEnd()
        Thread.sleep(300)
        assertNull("下播提醒关闭不应回调", streamEndDuration)
    }

    @Test
    fun `紧邻的开始和结束按调用顺序落库`() {
        prefs.setNotifyStreamEnd(false)

        tracker.recordStreamStart("1700000000", "短场")
        tracker.recordStreamEnd()

        waitFor("ordered start and end") {
            runBlocking {
                val sessions = AppDatabase.get(context).streamSessionDao().allSessions()
                sessions.size == 1 && sessions.single().endTs != null
            }
        }
        assertNull(AppDatabase.get(context).streamSessionDao().let { dao ->
            runBlocking { dao.findOpenSession() }
        })
    }

    // ---------- 升级/进程死亡场景（recordStreamStart 幂等 + NotLive reconcile） ----------

    @Test
    fun `recordStreamStart 同场幂等 复用不闭合不新插`() {
        val startMs = 1_700_000_000_000L
        runBlocking {
            AppDatabase.get(context).streamSessionDao()
                .insertSession(StreamSessionEntity(startTs = startMs, title = "同一场"))
        }
        tracker.recordStreamStart(startMs.toString(), "同一场")
        Thread.sleep(300) // 等 scope 协程 flush（幂等路径无 DB 写）
        val sessions = runBlocking {
            AppDatabase.get(context).streamSessionDao().recentSessions(5)
        }
        assertEquals("同一场不得新插行", 1, sessions.size)
        assertNull("开放行不得被闭合", sessions[0].endTs)
        assertEquals(startMs, sessions[0].startTs)
    }

    @Test
    fun `recordStreamStart 异场 闭合旧行插新行`() {
        val oldStart = 1_700_000_000_000L
        val newStart = 1_700_100_000_000L
        runBlocking {
            AppDatabase.get(context).streamSessionDao()
                .insertSession(StreamSessionEntity(startTs = oldStart, title = "旧场次"))
        }
        tracker.recordStreamStart(newStart.toString(), "新场次")
        waitFor("new session inserted") {
            runBlocking { AppDatabase.get(context).streamSessionDao().recentSessions(5).size == 2 }
        }
        val sessions = runBlocking {
            AppDatabase.get(context).streamSessionDao().recentSessions(5)
        }
        val old = sessions.first { it.startTs == oldStart }
        val new = sessions.first { it.startTs == newStart }
        assertEquals("旧场次闭合到新开时间", newStart, old.endTs)
        assertNull("新场次保持开放", new.endTs)
    }

    @Test
    fun `recordStreamStart liveStartTime缺失 走原路径闭合加新插`() {
        val oldStart = System.currentTimeMillis() - 3_600_000
        runBlocking {
            AppDatabase.get(context).streamSessionDao()
                .insertSession(StreamSessionEntity(startTs = oldStart, title = "残留"))
        }
        tracker.recordStreamStart(null, "新场次")
        waitFor("new session inserted") {
            runBlocking { AppDatabase.get(context).streamSessionDao().recentSessions(5).size == 2 }
        }
        val sessions = runBlocking {
            AppDatabase.get(context).streamSessionDao().recentSessions(5)
        }
        assertNotNull("旧行被闭合", sessions.first { it.startTs == oldStart }.endTs)
        assertEquals(1, sessions.count { it.endTs == null })
    }

    @Test
    fun `reconcile 有存活证据 静默闭合到证据时间`() {
        val now = System.currentTimeMillis()
        val start = now - 2 * 3_600_000
        val observed = now - 30 * 60_000
        prefs.setLastLiveObservedTime(observed)
        runBlocking {
            AppDatabase.get(context).streamSessionDao()
                .insertSession(StreamSessionEntity(startTs = start, title = "残留"))
        }
        prefs.setNotifyStreamEnd(true) // reconcile 也必须静默，不得触发下播回调
        tracker.reconcileOpenSessionIfNotLive()
        waitFor("reconciled") {
            runBlocking {
                AppDatabase.get(context).streamSessionDao().recentSessions(5)
                    .firstOrNull()?.endTs != null
            }
        }
        val session = runBlocking {
            AppDatabase.get(context).streamSessionDao().recentSessions(5).first()
        }
        assertEquals("闭合到存活证据时间", observed, session.endTs)
        Thread.sleep(300)
        assertNull("reconcile 不得触发下播回调", streamEndDuration)
    }

    @Test
    fun `reconcile 证据早于开场 夹到开场 0时长`() {
        val now = System.currentTimeMillis()
        val start = now - 3_600_000
        prefs.setLastLiveObservedTime(now - 2 * 3_600_000) // 比开场还早（失真）
        runBlocking {
            AppDatabase.get(context).streamSessionDao()
                .insertSession(StreamSessionEntity(startTs = start))
        }
        tracker.reconcileOpenSessionIfNotLive()
        waitFor("reconciled") {
            runBlocking {
                AppDatabase.get(context).streamSessionDao().recentSessions(5)
                    .firstOrNull()?.endTs != null
            }
        }
        val session = runBlocking {
            AppDatabase.get(context).streamSessionDao().recentSessions(5).first()
        }
        assertEquals(start, session.endTs)
    }

    @Test
    fun `reconcile 无开放行 no-op`() {
        tracker.reconcileOpenSessionIfNotLive()
        Thread.sleep(300)
        val sessions = runBlocking {
            AppDatabase.get(context).streamSessionDao().recentSessions(5)
        }
        assertEquals(0, sessions.size)
        assertNull(streamEndDuration)
    }

    @Test
    fun `recordPopularity 挂到开放场次 无开放场次或null不记`() {
        val dao = AppDatabase.get(context).streamSessionDao()
        // 无开放场次 → 不记
        tracker.recordPopularity(100)
        Thread.sleep(300)
        assertEquals(0, runBlocking { dao.popularityPoints(1).size })

        // 有开放场次 → 记录
        runBlocking { dao.insertSession(StreamSessionEntity(startTs = 1000)) }
        val openId = runBlocking { dao.findOpenSession()!!.id }
        tracker.recordPopularity(null) // null 不记
        tracker.recordPopularity(100)
        tracker.recordPopularity(120)
        waitFor("2 points") {
            runBlocking { dao.popularityPoints(openId).size == 2 }
        }
        assertEquals(
            // IO 协程并行写入且两点 ts 同毫秒级，顺序不保证，按值排序断言
            listOf(100, 120),
            runBlocking { dao.popularityPoints(openId).map { it.online }.sorted() }
        )
    }

    @Test
    fun `collectStreamCover 幂等 有cover跳过 无开放行跳过`() {
        val dao = AppDatabase.get(context).streamSessionDao()
        var fetchCount = 0
        tracker.coverUrlFetcher = { fetchCount++; "https://i0.hdslb.com/cover/x.jpg" }
        // 假 CoverStore：直接落一个假文件
        tracker.coverStore = object : com.bilibili.livemonitor.util.CoverStore() {
            override suspend fun acquire(context: android.content.Context, coverUrl: String): String? {
                val f = java.io.File(context.filesDir, "covers/fake.jpg")
                f.parentFile?.mkdirs()
                f.writeBytes(byteArrayOf(1, 2, 3))
                return f.absolutePath
            }
        }

        // 无开放行 → 不拉取
        tracker.collectStreamCover(11258892)
        Thread.sleep(300)
        assertEquals(0, fetchCount)

        // 有开放行无封面 → 拉一次写回
        runBlocking { dao.insertSession(StreamSessionEntity(startTs = 1000)) }
        tracker.collectStreamCover(11258892)
        waitFor("cover saved") {
            runBlocking { dao.recentSessions(1).first().coverPath != null }
        }
        assertEquals(1, fetchCount)

        // 已有封面 → 不再拉
        tracker.collectStreamCover(11258892)
        Thread.sleep(300)
        assertEquals("已有封面幂等跳过", 1, fetchCount)
        java.io.File(context.filesDir, "covers/fake.jpg").delete()
    }

    @Test
    fun `maybeSnapshotFollower 天闸 20h 内不重复采`() {
        val dao = AppDatabase.get(context).streamSessionDao()
        runBlocking { dao.deleteAllFollowerSnapshots() }
        var fetchCount = 0
        tracker.followerNumFetcher = { fetchCount++; 22420L }

        val now = System.currentTimeMillis()
        tracker.maybeSnapshotFollower(now)
        waitFor("first snapshot") {
            runBlocking { dao.followerSnapshots().isNotEmpty() }
        }
        assertEquals(1, fetchCount)

        // 1h 后：天闸未过 → 不再采
        tracker.maybeSnapshotFollower(now + 3_600_000)
        Thread.sleep(300)
        assertEquals("20h 内不重复采", 1, fetchCount)

        // 21h 后：过闸 → 再采
        tracker.maybeSnapshotFollower(now + 21 * 3_600_000)
        waitFor("second snapshot") {
            runBlocking { dao.followerSnapshots().size == 2 }
        }
        assertEquals(2, fetchCount)

        // 拉取失败不写点
        tracker.followerNumFetcher = { fetchCount++; null }
        tracker.maybeSnapshotFollower(now + 42 * 3_600_000)
        Thread.sleep(300)
        assertEquals(2, runBlocking { dao.followerSnapshots().size })
        runBlocking { dao.deleteAllFollowerSnapshots() }
    }
}
