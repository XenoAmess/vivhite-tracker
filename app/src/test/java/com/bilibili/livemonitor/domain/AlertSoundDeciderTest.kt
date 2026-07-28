package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒铃声源决策（纯函数，无 Android 依赖）。
 * 覆盖 4 种前缀解析、空值默认、非法值兜底、编码 round-trip。
 */
class AlertSoundDeciderTest {

    // ---------- resolve ----------

    @Test
    fun `null 输入返回 Default`() {
        assertEquals(SoundSource.Default, AlertSoundDecider.resolve(null))
    }

    @Test
    fun `空串输入返回 Default`() {
        assertEquals(SoundSource.Default, AlertSoundDecider.resolve(""))
    }

    @Test
    fun `空白串输入返回 Default`() {
        assertEquals(SoundSource.Default, AlertSoundDecider.resolve("   "))
    }

    @Test
    fun `builtin 前缀解析为 BuiltIn`() {
        val source = AlertSoundDecider.resolve("builtin:alert_default_1")
        assertTrue(source is SoundSource.BuiltIn)
        assertEquals("alert_default_1", (source as SoundSource.BuiltIn).key)
    }

    @Test
    fun `system 前缀解析为 System`() {
        val uri = "content://settings/system/alarm_alert"
        val source = AlertSoundDecider.resolve("system:$uri")
        assertTrue(source is SoundSource.System)
        assertEquals(uri, (source as SoundSource.System).uri)
    }

    @Test
    fun `file 前缀解析为 File`() {
        val uri = "content://com.android.providers.downloads.documents/456"
        val source = AlertSoundDecider.resolve("file:$uri")
        assertTrue(source is SoundSource.File)
        assertEquals(uri, (source as SoundSource.File).uri)
    }

    @Test
    fun `未知前缀返回 Default`() {
        // 场景：旧版本数据损坏 / 手动篡改 / 未来新增前缀但旧版本不认识
        assertEquals(SoundSource.Default, AlertSoundDecider.resolve("unknown:xxx"))
        assertEquals(SoundSource.Default, AlertSoundDecider.resolve("随便一段无前缀文本"))
    }

    @Test
    fun `builtin 前缀但 key 为空仍返回 BuiltIn`() {
        // 解析层不做 enum 校验，加载层负责兜底
        val source = AlertSoundDecider.resolve("builtin:")
        assertTrue(source is SoundSource.BuiltIn)
        assertEquals("", (source as SoundSource.BuiltIn).key)
    }

    @Test
    fun `system uri 包含冒号也能正确解析`() {
        // content:// uri 本身不含冒号前缀之外的部分，但确保 removePrefix 只去前缀
        val uri = "content://media/external/audio/media/123"
        val source = AlertSoundDecider.resolve("system:$uri")
        assertTrue(source is SoundSource.System)
        assertEquals(uri, (source as SoundSource.System).uri)
    }

    // ---------- encode round-trip ----------

    @Test
    fun `encodeBuiltIn round trip`() {
        val key = "alert_gentle"
        val encoded = AlertSoundDecider.encodeBuiltIn(key)
        val resolved = AlertSoundDecider.resolve(encoded)
        assertTrue(resolved is SoundSource.BuiltIn)
        assertEquals(key, (resolved as SoundSource.BuiltIn).key)
    }

    @Test
    fun `encodeSystem round trip`() {
        val uri = "content://settings/system/alarm_alert"
        val encoded = AlertSoundDecider.encodeSystem(uri)
        val resolved = AlertSoundDecider.resolve(encoded)
        assertTrue(resolved is SoundSource.System)
        assertEquals(uri, (resolved as SoundSource.System).uri)
    }

    @Test
    fun `encodeFile round trip`() {
        val uri = "content://com.android.providers.downloads.documents/456"
        val encoded = AlertSoundDecider.encodeFile(uri)
        val resolved = AlertSoundDecider.resolve(encoded)
        assertTrue(resolved is SoundSource.File)
        assertEquals(uri, (resolved as SoundSource.File).uri)
    }

    @Test
    fun `三种编码前缀互不冲突`() {
        val sameBody = "alert_default_1"
        val builtin = AlertSoundDecider.encodeBuiltIn(sameBody)
        val system = AlertSoundDecider.encodeSystem(sameBody)
        val file = AlertSoundDecider.encodeFile(sameBody)
        // 同 body 不同前缀应解析到不同档
        assertTrue(AlertSoundDecider.resolve(builtin) is SoundSource.BuiltIn)
        assertTrue(AlertSoundDecider.resolve(system) is SoundSource.System)
        assertTrue(AlertSoundDecider.resolve(file) is SoundSource.File)
    }
}
