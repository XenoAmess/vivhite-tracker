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

    // ---------- 新增：版本信息 / GitHub / 打开直播间 / 功能说明 ----------

    @Test
    fun `版本行显示版本号和8位git哈希`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvVersion).text.toString()
        assertTrue("应含版本号: $text", text.startsWith("v${BuildConfig.VERSION_NAME}"))
        assertTrue(
            "应含 8 位哈希: $text",
            Regex("\\([0-9a-f]{8}\\)").containsMatchIn(text)
        )
    }

    @Test
    fun `点GitHub按钮 打开项目地址`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenGithub
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("https://github.com/XenoAmess/vivhite-tracker", started?.dataString)
    }

    @Test
    fun `B站App可用时 打开直播间按钮为绿色`() {
        makeBilibiliResolvable(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val expected = androidx.core.content.ContextCompat.getColor(activity, R.color.green_500)
        val actual = activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).backgroundTintList?.defaultColor
        assertEquals(expected, actual)
    }

    @Test
    fun `B站App不可用时 按钮为灰色且点击跳转浏览器`() {
        makeBilibiliResolvable(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val expected = androidx.core.content.ContextCompat.getColor(activity, android.R.color.darker_gray)
        val actual = activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).backgroundTintList?.defaultColor
        assertEquals(expected, actual)

        LiveCheckService.isRunning = false
        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("https://live.bilibili.com/11258892", started?.dataString)
    }

    @Test
    fun `B站App可用时 点击直接打开B站App`() {
        makeBilibiliResolvable(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        LiveCheckService.isRunning = false

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("bilibili://live/11258892", started?.dataString)
    }

    @Test
    fun `监控中点打开直播间 先停止监控再跳转`() {
        // 用户需求：进直播间看就不再需要监控提醒
        makeBilibiliResolvable(true)
        prefs.setServiceRunning(true)
        LiveCheckService.isRunning = true
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // 消费冷启动自动恢复的启动 intent
        shadowOf(context).peekNextStartedService()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val stopIntent = shadowOf(context).peekNextStartedService()
        assertEquals("应先发出停止命令", LiveCheckService.ACTION_STOP_SERVICE, stopIntent?.action)
        assertFalse(prefs.isServiceRunning())
        val jumpIntent = shadowOf(context).nextStartedActivity
        assertEquals("bilibili://live/11258892", jumpIntent?.dataString)
    }

    @Test
    fun `点右上角信息图标 弹出功能说明`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<android.widget.ImageButton>(R.id.btnInfo).performClick()

        // AppCompat AlertDialog 要用 ShadowDialog.getLatestDialog()
        // （ShadowAlertDialog.getLatestAlertDialog() 只跟踪 framework AlertDialog）
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(dialog != null)
        // 不猜 message id，遍历 decor view 找包含功能说明的 TextView
        val texts = mutableListOf<String>()
        fun collectTexts(view: android.view.View) {
            if (view is android.widget.TextView) texts.add(view.text.toString())
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) collectTexts(view.getChildAt(i))
            }
        }
        dialog.window?.decorView?.let { collectTexts(it) }
        assertTrue("应含功能说明: $texts", texts.any { it.contains("每分钟检查") })
    }

    private fun makeBilibiliResolvable(resolvable: Boolean) {
        val pm = shadowOf(context.packageManager)
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("bilibili://live/11258892"))
        if (resolvable) {
            pm.addResolveInfoForIntent(
                intent,
                android.content.pm.ResolveInfo().apply {
                    activityInfo = android.content.pm.ActivityInfo().apply {
                        packageName = "tv.danmaku.bili"
                        name = "tv.danmaku.bili.MainActivity"
                    }
                }
            )
        } else {
            // ShadowPackageManager.resolveInfoForIntent 是 static Map，跨测试方法泄漏，
            // false 分支必须按包名显式移除（空列表会在 map 里留下 key，行为不等价于无条目）
            pm.removeResolveInfosForIntent(intent, "tv.danmaku.bili")
        }
    }
}
