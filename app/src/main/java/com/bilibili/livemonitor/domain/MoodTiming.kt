package com.bilibili.livemonitor.domain

/**
 * 心情事件的时长/结束时间换算（纯函数）。
 * 事件按日归属，结束时间允许跨午夜（结束 <= 开始 视为次日）。
 */
object MoodTiming {

    private const val DAY_MS = 86_400_000L
    private const val MIN_MS = 60_000L

    fun endTs(startTs: Long, durationMin: Int): Long = startTs + durationMin * MIN_MS

    /** 由开始/结束反推时长（分钟）；结束不晚于开始视为跨到次日 */
    fun durationMinFromEnd(startTs: Long, endTs: Long): Int {
        var diff = endTs - startTs
        if (diff <= 0) diff += DAY_MS
        return (diff / MIN_MS).toInt()
    }
}
