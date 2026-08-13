package com.bilibili.livemonitor.domain

/**
 * 直播标题高频词（纯函数）：分词用「非字母数字 CJK 字符」切分，
 * 过滤长度 <2 的碎片，返回 (词, 次数) 按频次倒序。
 */
object TitleWordCloud {

    private val SPLITTER = Regex("[^\\p{IsHan}\\p{IsAlphabetic}\\p{IsDigit}]+")

    fun tokenize(text: String): List<String> =
        text.split(SPLITTER)
            .map { it.trim() }
            .filter { it.length >= 2 }

    fun topWords(titles: List<String>, limit: Int = 20): List<Pair<String, Int>> =
        titles.flatMap { tokenize(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.toPair() }
}
