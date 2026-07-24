package com.bilibili.livemonitor

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * AlertActivity 用户场景（P4）。
 * 真机场景：开播提醒全屏弹出后，用户不能误触返回键消失，
 * 30 秒无操作自动关闭（真机实测观察到的行为），
 * 点"去看直播"跳 B 站并关闭。
 */
@RunWith(RobolectricTestRunner::class)
class AlertActivityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `返回键被拦截 提醒页不关闭`() {
        // AGP 9 迁移时用 OnBackPressedDispatcher 重写的逻辑，防止退回旧 API
        // setup() = create+start+resume：OnBackPressedCallback 绑定生命周期，
        // 只有 STARTED 之后才会拦截返回事件
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        val activity = controller.get()

        activity.onBackPressedDispatcher.onBackPressed()

        assertFalse("返回手势不得关闭提醒页", activity.isFinishing)
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

        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
