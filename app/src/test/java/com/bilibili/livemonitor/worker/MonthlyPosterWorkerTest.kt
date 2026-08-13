package com.bilibili.livemonitor.worker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class MonthlyPosterWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().build()
        )
        prefs = PreferenceManager(context)
        prefs.setLastPosterMonth("")
        runBlocking {
            AppDatabase.get(context).streamSessionDao().deleteAll()
            AppDatabase.get(context).moodEventDao().deleteAll()
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }

    private fun worker() = TestListenableWorkerBuilder<MonthlyPosterWorker>(context).build()

    private fun notificationCount(): Int =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size

    private fun lastMonthSession(): StreamSessionEntity {
        val c = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        return StreamSessionEntity(
            startTs = c.timeInMillis + 3_600_000,
            endTs = c.timeInMillis + 3 * 3_600_000,
            title = "上月的一场"
        )
    }

    @Test
    fun `上月有记录 生成海报发通知并落月份键`() = runBlocking {
        runBlocking { AppDatabase.get(context).streamSessionDao().insertSession(lastMonthSession()) }
        val result = worker().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.Success::class.java, result.javaClass)
        assertTrue("应有通知", notificationCount() > 0)
        assertTrue("月份键已落", prefs.getLastPosterMonth().isNotBlank())

        // 同月重跑：跳过，不再发通知
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        val again = worker().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.Success::class.java, again.javaClass)
        assertEquals("同月不得重复生成", 0, notificationCount())
    }

    @Test
    fun `空月跳过 落键不发通知`() = runBlocking {
        val result = worker().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.Success::class.java, result.javaClass)
        assertEquals(0, notificationCount())
        assertTrue(prefs.getLastPosterMonth().isNotBlank())
    }
}
