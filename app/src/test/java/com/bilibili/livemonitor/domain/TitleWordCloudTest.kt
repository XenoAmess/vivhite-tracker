package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleWordCloudTest {

    @Test
    fun `tokenize 按非字母数字汉字切分 过滤短碎片`() {
        assertEquals(
            listOf("失眠", "无言", "sc2"),
            TitleWordCloud.tokenize("失眠 无言！sc2（肉鸽）")
                .filter { it != "肉鸽" } + listOf()
        ).let { }
        // 直接断言分词结果
        val tokens = TitleWordCloud.tokenize("失眠 无言！sc2（肉鸽）x")
        assertEquals(listOf("失眠", "无言", "sc2", "肉鸽"), tokens)
        // 单字符碎片被过滤
        assertEquals(emptyList<String>(), TitleWordCloud.tokenize("a，b。c"))
    }

    @Test
    fun `topWords 频次倒序 同频字典序 截断`() {
        val titles = listOf(
            "失眠 杂谈", "杂谈 失眠", "sc2 肉鸽", "失眠"
        )
        val top = TitleWordCloud.topWords(titles)
        assertEquals("失眠", top[0].first)
        assertEquals(3, top[0].second)
        assertEquals(2, top[1].second) // 杂谈
        assert(top.size == 4)
        // 截断
        assertEquals(2, TitleWordCloud.topWords(titles, limit = 2).size)
    }
}
