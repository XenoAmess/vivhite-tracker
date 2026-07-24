package com.bilibili.livemonitor

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * MainActivity 用户场景（P4）。
 * 真机场景：重装/冷启动后打开 App 自动恢复监控、按钮启停、状态一眼可见。
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
        LiveCheckService.isRunning = false
        LiveCheckService.isUserStopped = false
        // 授权通知权限，否则点开始监控会走权限申请分支而不启动服务
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        LiveCheckService.isRunning = false
        LiveCheckService.isUserStopped = false
    }

    @Test
    fun `冷启动且监控标记为true 自动恢复监控`() {
        // 真机场景：服务被系统杀掉后用户重新打开 App，无需手动点开始就恢复监控
        prefs.setServiceRunning(true)
        LiveCheckService.isRunning = false

        Robolectric.buildActivity(MainActivity::class.java).create()

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService::class.java.name, started?.component?.className)
    }

    @Test
    fun `冷启动且监控标记为false 不启动服务`() {
        prefs.setServiceRunning(false)

        Robolectric.buildActivity(MainActivity::class.java).create()

        assertNull(shadowOf(context).peekNextStartedService())
    }

    @Test
    fun `点开始监控 启动服务且按钮变为停止监控`() {
        prefs.setServiceRunning(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnToggle
        ).performClick()

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService::class.java.name, started?.component?.className)
        assertTrue(prefs.isServiceRunning())
        assertEquals(
            "停止监控",
            activity.findViewById<com.google.android.material.button.MaterialButton>(
                R.id.btnToggle
            ).text.toString()
        )
    }

    @Test
    fun `点停止监控 发送停止命令`() {
        prefs.setServiceRunning(true)
        LiveCheckService.isRunning = true
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // 消费掉冷启动自动恢复发出的启动 intent
        shadowOf(context).peekNextStartedService()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnToggle
        ).performClick()

        val stopIntent = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService.ACTION_STOP_SERVICE, stopIntent?.action)
        assertFalse(prefs.isServiceRunning())
    }

    @Test
    fun `有检测记录时 显示上次检测时间和状态`() {
        prefs.setLastCheck(System.currentTimeMillis(), isLive = true, success = true)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvLastCheck).text.toString()
        assertTrue(text.contains("上次检测"))
        assertTrue(text.contains("直播中"))
    }

    @Test
    fun `检测失败时 状态显示检测失败`() {
        // 真机场景：Doze 网络错误落盘 success=false，用户打开 App 要能看到异常
        prefs.setLastCheck(System.currentTimeMillis(), isLive = false, success = false)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvLastCheck).text.toString()
        assertTrue(text.contains("检测失败"))
    }

    @Test
    fun `点击查看运行日志 打开LogActivity`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnViewLog
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(LogActivity::class.java.name, started?.component?.className)
    }
}
