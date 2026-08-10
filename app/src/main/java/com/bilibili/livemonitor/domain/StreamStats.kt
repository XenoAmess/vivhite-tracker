package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.StreamSessionEntity

/**
 * 场次统计聚合（纯函数，输入 DAO 数据 → 摘要）。
 */
object StreamStats {

    data class Summary(
        val weekCount: Int,
        val monthCount: Int,
        val avgDurationMs: Long,
        val maxDurationMs: Long,
        val totalDurationMs: Long
    )

    private const val DAY_MS = 86_400_000L

    /** 只统计已闭合（endTs > startTs）的场次，防止进程死亡残留脏行污染统计 */
    fun summarize(sessions: List<StreamSessionEntity>, now: Long): Summary {
        val closed = sessions.filter { it.endTs != null && it.endTs!! > it.startTs }
        val weekCount = closed.count { it.startTs >= now - 7 * DAY_MS }
        val monthCount = closed.count { it.startTs >= now - 30 * DAY_MS }
        val durations = closed.map { it.endTs!! - it.startTs }
        val total = durations.sum()
        val avg = if (durations.isEmpty()) 0 else total / durations.size
        return Summary(
            weekCount = weekCount,
            monthCount = monthCount,
            avgDurationMs = avg,
            maxDurationMs = durations.maxOrNull() ?: 0,
            totalDurationMs = total
        )
    }

    /**
     * 最近 [days] 个本地日每天的开播场次数（下标 0 = 最早一天）。
     * [localDayOffsetMs] 把日界对齐到本地零点（Activity 传
     * TimeZone.getDefault().getOffset(now)，测试传 0 即 UTC）。
     */
    fun dailyCounts(
        sessions: List<StreamSessionEntity>,
        now: Long,
        days: Int,
        localDayOffsetMs: Long = 0
    ): List<Int> {
        val day0 = (now + localDayOffsetMs) / DAY_MS - (days - 1)
        val counts = IntArray(days)
        sessions.filter { it.endTs != null && it.endTs!! > it.startTs }.forEach { s ->
            val idx = ((s.startTs + localDayOffsetMs) / DAY_MS) - day0
            if (idx in 0 until days) counts[idx.toInt()]++
        }
        return counts.toList()
    }

    /**
     * 与 [dailyCounts] 逐桶对齐的星期标签（0=周日..6=周六，下标 0 = 最早一天）。
     * 桶 j 对应本地日 day0+j，星期映射同 [favoriteWeekday]：(epochDay+4)%7。
     */
    fun weekdayLabels(
        now: Long,
        days: Int,
        localDayOffsetMs: Long = 0
    ): List<Int> {
        val day0 = (now + localDayOffsetMs) / DAY_MS - (days - 1)
        return (0 until days).map { j -> Math.floorMod(day0 + j + 4, 7).toInt() }
    }

    /**
     * 星期偏好：0=周日..6=周六，各自累计已闭合场次数；无场次返回 null。
     * 星期映射 (epochDay+4)%7（epochDay 0 = 1970-01-01 周四 → 4）。
     */
    fun favoriteWeekday(
        sessions: List<StreamSessionEntity>,
        localDayOffsetMs: Long = 0
    ): Pair<Int, Int>? {
        val counts = IntArray(7)
        sessions.filter { it.endTs != null && it.endTs!! > it.startTs }.forEach { s ->
            val epochDay = (s.startTs + localDayOffsetMs) / DAY_MS
            counts[((epochDay + 4) % 7).toInt()]++
        }
        val best = counts.maxOrNull() ?: 0
        return if (best == 0) null else counts.indexOf(best) to best
    }
}
