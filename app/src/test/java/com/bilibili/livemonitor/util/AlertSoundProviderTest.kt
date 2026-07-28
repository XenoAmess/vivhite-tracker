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
    fun `BuiltInSound DEFAULT 是 CLASSIC_1`() {
        // 守护默认值不被意外修改
        assertEquals(BuiltInSound.CLASSIC_1, BuiltInSound.DEFAULT)
        assertEquals("alert_default_1", BuiltInSound.DEFAULT.key)
    }

    @Test
    fun `BuiltInSound fromKey 已知 key 返回对应项`() {
        assertEquals(BuiltInSound.CLASSIC_1, BuiltInSound.fromKey("alert_default_1"))
        assertEquals(BuiltInSound.CLASSIC_2, BuiltInSound.fromKey("alert_default_2"))
        assertEquals(BuiltInSound.GENTLE, BuiltInSound.fromKey("alert_gentle"))
        assertEquals(BuiltInSound.URGENT, BuiltInSound.fromKey("alert_urgent"))
    }

    @Test
    fun `BuiltInSound fromKey 未知 key 返回 null`() {
        assertNull(BuiltInSound.fromKey("nonexistent"))
        assertNull(BuiltInSound.fromKey(null))
    }

    @Test
    fun `BuiltInSound 有 4 个条目`() {
        // 守护铃声池条目数，新增/删除时提醒更新 UI 和测试
        assertEquals(4, BuiltInSound.values().size)
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
