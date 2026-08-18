package com.bilibili.livemonitor

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.service.LiveCheckService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * AlertActivity 用户场景（P4）。
 * 真机场景：开播提醒全屏弹出后，返回键与按钮都会可靠停铃，
 * 30 秒无操作自动关闭，
 * 点"去看直播"跳 B 站并关闭。
 */
@RunWith(RobolectricTestRunner::class)
class AlertActivityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `返回键停止提醒并关闭页面`() {
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals(
            LiveCheckService.ACTION_STOP_ALERT,
            shadowOf(activity).nextStartedService?.action
        )
        assertTrue("返回手势应关闭提醒页", activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `提醒页使用可滚动布局适配小屏`() {
        val controller = Robolectric.buildActivity(AlertActivity::class.java).create()

        val scroll = controller.get().findViewById<android.view.View>(android.R.id.content)
            .let { (it as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView }
        assertTrue(scroll.isFillViewport)
        assertEquals(
            android.view.Gravity.CENTER,
            (scroll.getChildAt(0) as android.widget.LinearLayout).gravity
        )
        controller.destroy()
    }

    @Test
    fun `30秒无操作 自动关闭`() {
        // 真机实测：提醒页弹出 30 秒后自动消失
        val controller = Robolectric.buildActivity(AlertActivity::class.java).create()
        val activity = controller.get()

        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)

        assertTrue("30 秒后应自动关闭", activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `点去看直播 打开直播间链接并关闭`() {
        val controller = Robolectric.buildActivity(AlertActivity::class.java).create()
        val activity = controller.get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnGoToLive
        ).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals(
            "https://live.bilibili.com/11258892",
            started?.dataString
        )
        assertEquals(
            LiveCheckService.ACTION_WATCH_LIVE,
            shadowOf(activity).nextStartedService?.action
        )
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `点知道了 直接关闭`() {
        val controller = Robolectric.buildActivity(AlertActivity::class.java).create()
        val activity = controller.get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnDismiss
        ).performClick()

        assertEquals(
            LiveCheckService.ACTION_STOP_ALERT,
            shadowOf(activity).nextStartedService?.action
        )
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
