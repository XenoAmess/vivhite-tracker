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
}
