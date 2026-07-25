package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 名言池与选取算法。
 * 特殊规则：邓煜首启必出且在常规池；白绮固定 1/10 且不进常规池。
 */
class QuotePickerTest {

    // ---------- 池完整性 ----------

    @Test
    fun `池非空且条目均完整`() {
        assertTrue(MathQuotes.pool.size >= 80)
        MathQuotes.pool.forEachIndexed { i, q ->
            assertTrue("第 $i 条文案为空", q.text.isNotBlank())
            assertTrue("第 $i 条作者为空", q.author.isNotBlank())
        }
    }

    @Test
    fun `池无重复条目`() {
        val keys = MathQuotes.pool.map { "${it.text}|${it.author}" }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `全部使用中文标点 不含半角标点`() {
        val halfWidth = Regex("[,;:!?]")
        MathQuotes.pool.forEach { q ->
            assertFalse("含半角标点: ${q.text}", halfWidth.containsMatchIn(q.text))
        }
        assertFalse(halfWidth.containsMatchIn(MathQuotes.SPECIAL_FIRST_LAUNCH.text))
        assertFalse(halfWidth.containsMatchIn(MathQuotes.SPECIAL_HIGH_FREQ.text))
    }

    @Test
    fun `邓煜在常规池中 白绮不在`() {
        assertTrue(MathQuotes.pool.contains(MathQuotes.SPECIAL_FIRST_LAUNCH))
        assertFalse(MathQuotes.pool.contains(MathQuotes.SPECIAL_HIGH_FREQ))
    }

    // ---------- randomExcept ----------

    @Test
    fun `randomExcept 永不返回被排除下标`() {
        repeat(200) {
            val index = MathQuotes.randomExcept(3, Random.Default)
            assertTrue(index != 3)
            assertTrue(index in MathQuotes.pool.indices)
        }
    }

    @Test
    fun `randomExcept 下标总在范围内`() {
        repeat(200) {
            assertTrue(MathQuotes.randomExcept(null, Random.Default) in MathQuotes.pool.indices)
        }
    }

    // ---------- 特殊规则 ----------

    @Test
    fun `首次启动必出邓煜`() {
        val quote = QuotePicker.pick(isFirstLaunchDone = false, lastIndex = null, random = Random(42))
        assertEquals(MathQuotes.SPECIAL_FIRST_LAUNCH, quote)
    }

    @Test
    fun `非首启且随机数命中 出白绮`() {
        // nextInt(10) == 0 时触发高频条目；找一个 nextInt(10) 返回 0 的种子
        val seed = (0..1000).first { s -> Random(s).nextInt(10) == 0 }
        val quote = QuotePicker.pick(isFirstLaunchDone = true, lastIndex = null, random = Random(seed))
        assertEquals(MathQuotes.SPECIAL_HIGH_FREQ, quote)
    }

    @Test
    fun `非首启且随机数未命中 出常规池条目`() {
        val seed = (0..1000).first { s -> Random(s).nextInt(10) != 0 }
        val quote = QuotePicker.pick(isFirstLaunchDone = true, lastIndex = null, random = Random(seed))
        assertTrue(MathQuotes.pool.contains(quote))
    }

    @Test
    fun `poolIndexOf 特殊高频条目返回null`() {
        assertNull(QuotePicker.poolIndexOf(MathQuotes.SPECIAL_HIGH_FREQ))
        assertNotNull(QuotePicker.poolIndexOf(MathQuotes.SPECIAL_FIRST_LAUNCH))
    }
}
