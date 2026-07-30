package com.bilibili.livemonitor.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AlertSoundProvider 的纯逻辑测试 + 三档兜底链。
 *
 * 兜底链用 FakeExoPlayer（动态代理）驱动：prepare 可配抛异常，
 * 模拟真实世界铃声源失效（SAF 权限丢失/文件被删/损坏 URI）。
 */
@RunWith(RobolectricTestRunner::class)
class AlertSoundProviderTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val provider = AlertSoundProvider()

    @Test
    fun `BuiltInSound DEFAULT 是 CL_1`() {
        // 守护默认值不被意外修改
        assertEquals(BuiltInSound.CL_1, BuiltInSound.DEFAULT)
        assertEquals("alert_1", BuiltInSound.DEFAULT.key)
    }

    @Test
    fun `BuiltInSound fromKey 已知 key 返回对应项`() {
        assertEquals(BuiltInSound.CL_1, BuiltInSound.fromKey("alert_1"))
        assertEquals(BuiltInSound.CL_2, BuiltInSound.fromKey("alert_2"))
        assertEquals(BuiltInSound.CL_3, BuiltInSound.fromKey("alert_3"))
        assertEquals(BuiltInSound.CL_4, BuiltInSound.fromKey("alert_4"))
        assertEquals(BuiltInSound.CL_5, BuiltInSound.fromKey("alert_5"))
        assertEquals(BuiltInSound.CL_6, BuiltInSound.fromKey("alert_6"))
        assertEquals(BuiltInSound.CL_7, BuiltInSound.fromKey("alert_7"))
    }

    @Test
    fun `BuiltInSound fromKey 未知 key 返回 null`() {
        assertNull(BuiltInSound.fromKey("nonexistent"))
        assertNull(BuiltInSound.fromKey(null))
    }

    @Test
    fun `BuiltInSound 有 7 个条目`() {
        // 守护铃声池条目数，新增/删除时提醒更新 UI 和测试
        assertEquals(7, BuiltInSound.values().size)
    }

    @Test
    fun `BuiltInSound 每个条目都有非空 title`() {
        // 守护 UI 展示不出现空名
        BuiltInSound.values().forEach { sound ->
            assertEquals(true, sound.title.isNotEmpty())
        }
    }

    @Test
    fun `BuiltInSound 每个条目的 key 互不重复`() {
        // 守护 prefs 编码不冲突
        val keys = BuiltInSound.values().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ---------- 三档兜底链（用户场景：自定义铃声失效时绝不能哑火） ----------

    @Test
    fun `file坏URI加载失败 回退内置默认海愿`() {
        // 用户场景：SAF 权限被系统回收/文件被删 → prepare 抛异常 →
        // 必须回退到内置默认，而不是静默不响
        val fake = FakeExoPlayer()
        fake.prepareShouldFail = { uri -> uri?.startsWith("content://media") == true }
        val ok = provider.setupDataSource(
            context, fake.player, "file:content://media/external/audio/lost.mp3"
        )

        assertTrue("回退后必须就绪", ok)
        assertEquals(
            "应加载内置默认资源",
            "android.resource://${context.packageName}/${R.raw.alert_1}",
            fake.lastMediaUri
        )
        assertEquals("应先尝试 file 再回退内置", 2, fake.allMediaUris.size)
    }

    @Test
    fun `未知builtin key 用默认兜底`() {
        // 用户场景：prefs 里存了旧版本的 alert_99，升级后 key 消失
        val fake = FakeExoPlayer()
        val ok = provider.setupDataSource(context, fake.player, "builtin:alert_99")

        assertTrue(ok)
        assertEquals(
            "android.resource://${context.packageName}/${R.raw.alert_1}",
            fake.lastMediaUri
        )
    }

    @Test
    fun `正常builtin选择 直接加载不触发兜底`() {
        val fake = FakeExoPlayer()
        val ok = provider.setupDataSource(context, fake.player, "builtin:alert_7")

        assertTrue(ok)
        assertTrue(fake.prepared)
        assertEquals(
            "android.resource://${context.packageName}/${R.raw.alert_7}",
            fake.lastMediaUri
        )
    }

    @Test
    fun `空prefs默认源 直接加载CL_1且不重复走兜底`() {
        // Default 源失败时不得再尝试一次 Default（setupDataSource 的 source !is Default 分支）
        val fake = FakeExoPlayer()
        val ok = provider.setupDataSource(context, fake.player, "")

        assertTrue(ok)
        assertEquals(
            "android.resource://${context.packageName}/${R.raw.alert_1}",
            fake.lastMediaUri
        )
    }

    @Test
    fun `system铃声URI 正常加载`() {
        val fake = FakeExoPlayer()
        val ok = provider.setupDataSource(
            context, fake.player, "system:content://settings/system/notification_sound"
        )

        assertTrue(ok)
        assertEquals("content://settings/system/notification_sound", fake.lastMediaUri)
    }

    @Test
    fun `内置默认也失败 走系统铃声链兜底`() {
        // 极端场景：用户选的 file 坏了，且连内置资源也加载失败（ROM 兼容问题）→
        // 最后兜底是系统闹钟铃声，绝不能无声
        val fake = FakeExoPlayer()
        // file URI（content://media）和内置资源（android.resource://）都失败，
        // 只有系统铃声 URI（content://settings）放行
        fake.prepareShouldFail = { uri -> uri?.startsWith("content://settings") != true }
        val ok = provider.setupDataSource(
            context, fake.player, "file:content://media/external/audio/lost.mp3"
        )

        assertTrue("系统铃声兜底后必须就绪", ok)
        assertEquals("应尝试 file→builtin→system 三次", 3, fake.allMediaUris.size)
        assertTrue(
            "最终应落在系统铃声",
            fake.lastMediaUri?.startsWith("content://settings") == true
        )
    }

    @Test
    fun `全链失败 返回false由调用方静默`() {
        // 最极端：所有源都加载失败（系统铃声 URI 也读不出）→ 返回 false，
        // 调用方（LiveCheckService/AlertActivity）静默跳过本次响铃
        val fake = FakeExoPlayer()
        fake.prepareThrows = true
        val ok = provider.setupDataSource(context, fake.player, "builtin:alert_3")

        assertFalse(ok)
    }
}
