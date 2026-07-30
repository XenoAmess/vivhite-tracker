package com.bilibili.livemonitor.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DefaultQqSdkSharer 的授权持久化决策（不触达 QQ SDK 本体的可测部分）。
 *
 * 用户场景：授权一次后 60 天内分享不再弹授权页；
 * 授权过期后必须重新走授权流程，而不是静默分享失败。
 */
@RunWith(RobolectricTestRunner::class)
class DefaultQqSdkSharerTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var sharer: DefaultQqSdkSharer

    @Before
    fun setUp() {
        context.getSharedPreferences("qq_share_state", Context.MODE_PRIVATE)
            .edit().clear().apply()
        sharer = DefaultQqSdkSharer()
        sharer.bind(context)
    }

    @Test
    fun `手动授权未过期 isAuthorized为true`() {
        // 用户场景：昨天授权过 QQ，今天分享直播间不应再弹授权页
        context.getSharedPreferences("qq_share_state", Context.MODE_PRIVATE).edit()
            .putBoolean("authorized", true)
            .putLong("expires_at", System.currentTimeMillis() + 3600_000)
            .apply()

        assertTrue(sharer.isAuthorized())
    }

    @Test
    fun `手动授权已过期 isAuthorized为false且prefs被清理`() {
        // 授权过期（60 天前授权）→ 必须重新授权，且过期标记要被清掉
        context.getSharedPreferences("qq_share_state", Context.MODE_PRIVATE).edit()
            .putBoolean("authorized", true)
            .putLong("expires_at", System.currentTimeMillis() - 3600_000)
            .apply()

        assertFalse("过期授权不得算数", sharer.isAuthorized())
        val prefs = context.getSharedPreferences("qq_share_state", Context.MODE_PRIVATE)
        assertFalse("过期后 authorized 标记必须清除", prefs.getBoolean("authorized", false))
    }

    @Test
    fun `无任何授权记录 isAuthorized为false`() {
        assertFalse(sharer.isAuthorized())
    }

    @Test
    fun `onActivityResult无pending登录监听 不崩只记日志`() {
        // 防御：系统回调在登录流程外到达（比如分享请求被系统回收后回来），
        // 绝不能 NPE 崩掉主界面
        sharer.onActivityResult(11101, -1, null)
        // 不抛异常即通过
    }
}
