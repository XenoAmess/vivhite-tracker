package com.bilibili.livemonitor.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().build()
        )
        prefs = PreferenceManager(context)
        prefs.setAutoBackupEnabled(false)
        prefs.setBackupTreeUri("")
        prefs.setLastBackupTime(0)
    }

    private fun worker(): BackupWorker =
        androidx.work.testing.TestListenableWorkerBuilder<BackupWorker>(context).build()

    @Test
    fun `未开开关跳过且不写时间戳`() = runBlocking {
        prefs.setBackupTreeUri("content://com.example/tree/dir")
        val result = worker().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.Success::class.java, result.javaClass)
        assertEquals(0L, prefs.getLastBackupTime())
    }

    @Test
    fun `未选目录跳过`() = runBlocking {
        prefs.setAutoBackupEnabled(true)
        val result = worker().doWork()
        assertEquals(androidx.work.ListenableWorker.Result.Success::class.java, result.javaClass)
        assertEquals(0L, prefs.getLastBackupTime())
    }

    @Test
    fun `目录不可写返回 retry`() = runBlocking {
        prefs.setAutoBackupEnabled(true)
        prefs.setBackupTreeUri("content://invalid.authority/tree/nonexistent")
        val result = worker().doWork()
        // 假目录 createDocument 必抛 → retry
        assertTrue(result is androidx.work.ListenableWorker.Result.Retry)
    }
}
