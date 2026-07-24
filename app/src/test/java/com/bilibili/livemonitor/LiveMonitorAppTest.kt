package com.bilibili.livemonitor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LiveMonitorApp 启动逻辑（P4）。
 * 真机场景：进程被系统重建时（Application.onCreate），
 * 如果监控应该在运行，WorkManager 兜底任务必须重新注册。
 */
@RunWith(RobolectricTestRunner::class)
class LiveMonitorAppTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().build()
        )
        prefs = PreferenceManager(context)
    }

    @Test
    fun `监控标记为true 进程重建时注册周期worker`() {
        prefs.setServiceRunning(true)

        (context as LiveMonitorApp).onCreate()

        val periodic = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_periodic").get()
        assertTrue(
            "应注册周期兜底任务",
            periodic.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `监控标记为false 不注册worker`() {
        prefs.setServiceRunning(false)

        (context as LiveMonitorApp).onCreate()

        val periodic = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("live_check_periodic").get()
        assertTrue(periodic.isEmpty())
    }
}
