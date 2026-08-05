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
        runBlocking { AppDatabase.get(context).streamSessionDao().deleteAll() }
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
}
