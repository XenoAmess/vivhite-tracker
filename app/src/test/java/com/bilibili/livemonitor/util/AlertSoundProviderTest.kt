package com.bilibili.livemonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AlertSoundProvider 的纯逻辑测试。
 *
 * setupDataSource 的三档兜底链依赖 Robolectric 的 ShadowMediaPlayer 对
 * `android.resource://` scheme 的支持（ShadowContentResolver 不支持），
 * 因此三档兜底通过 AlertActivityTest / LiveCheckServiceTest 间接覆盖，
 * 此处只测 BuiltInSound enum 守护。
 */
class AlertSoundProviderTest {

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
    }

    @Test
    fun `BuiltInSound fromKey 未知 key 返回 null`() {
        assertNull(BuiltInSound.fromKey("nonexistent"))
        assertNull(BuiltInSound.fromKey(null))
    }

    @Test
    fun `BuiltInSound 有 6 个条目`() {
        // 守护铃声池条目数，新增/删除时提醒更新 UI 和测试
        assertEquals(6, BuiltInSound.values().size)
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
}
