package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodCatalogTest {

    @Test
    fun `key 全局唯一`() {
        val keys = MoodCatalog.moods.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `每个心情都有 emoji 和中文文案`() {
        MoodCatalog.moods.forEach { m ->
            assertTrue("${m.key} 缺 emoji", m.emoji.isNotBlank())
            assertTrue("${m.key} 缺文案", m.label.isNotBlank())
        }
    }

    @Test
    fun `分组排序 积极在前消极在后`() {
        val groups = MoodCatalog.moods.map { it.group }
        val pos = groups.indexOfLast { it == MoodCatalog.Group.POSITIVE }
        val neuFirst = groups.indexOfFirst { it == MoodCatalog.Group.NEUTRAL }
        val neuLast = groups.indexOfLast { it == MoodCatalog.Group.NEUTRAL }
        val negFirst = groups.indexOfFirst { it == MoodCatalog.Group.NEGATIVE }
        assertTrue(pos < neuFirst)
        assertTrue(neuLast < negFirst)
    }

    @Test
    fun `display 已知 key 拼接 未知 key 兜底原文`() {
        assertEquals("😄开心", MoodCatalog.display("happy"))
        assertEquals("unknown_key", MoodCatalog.display("unknown_key"))
        assertNotNull(MoodCatalog.find("sad"))
        assertEquals(null, MoodCatalog.find("nope"))
    }
}
