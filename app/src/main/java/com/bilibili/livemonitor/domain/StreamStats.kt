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
     * 自然月摘要（导出海报按月维度用）：已闭合场次数 / 平均时长 ms / 最长时长 ms。
     * 未闭合与脏行（endTs <= startTs）一律忽略，与 [summarize] 口径一致。
     */
    fun monthSummary(sessions: List<StreamSessionEntity>): Triple<Int, Long, Long> {
        val closed = sessions.filter { it.endTs != null && it.endTs!! > it.startTs }
        val durations = closed.map { it.endTs!! - it.startTs }
        val total = durations.sum()
        val avg = if (durations.isEmpty()) 0L else total / durations.size
        return Triple(durations.size, avg, durations.maxOrNull() ?: 0L)
    }

    /**
     * 本月逐周场次（导出海报柱状图用）：按开播日归 5 桶
     * [1-7] [8-14] [15-21] [22-28] [29-月末]。只计已闭合场次；
     * 不在本月范围内的场次忽略（防调用方未预过滤）。
     */
    fun weeklyCounts(
        sessions: List<StreamSessionEntity>,
        monthStartMs: Long,
        daysInMonth: Int
    ): List<Int> {
        val counts = IntArray(5)
        sessions.filter { it.endTs != null && it.endTs!! > it.startTs }.forEach { s ->
            val dayOffset = ((s.startTs - monthStartMs) / DAY_MS).toInt()
            if (dayOffset in 0 until daysInMonth) {
                counts[(dayOffset / 7).coerceAtMost(4)]++
            }
        }
        return counts.toList()
    }

    /**
     * 近 [months] 个自然月每月场次数（含本月，下标 0 = 最早月）。只算已闭合场次。
     * 月界用本地 Calendar（月长不等，不能用固定 30 天窗口近似）。
     */
    fun monthlyCounts(
        sessions: List<StreamSessionEntity>,
        now: Long,
        months: Int
    ): List<Int> {
        val monthStart = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.MONTH, -(months - 1))
        }
        val buckets = IntArray(months)
        sessions.filter { it.endTs != null && it.endTs!! > it.startTs }.forEach { s ->
            val c = java.util.Calendar.getInstance().apply { timeInMillis = s.startTs }
            val idx = (c.get(java.util.Calendar.YEAR) - monthStart.get(java.util.Calendar.YEAR)) * 12 +
                (c.get(java.util.Calendar.MONTH) - monthStart.get(java.util.Calendar.MONTH))
            if (idx in 0 until months) buckets[idx]++
        }
        return buckets.toList()
    }

    /**
     * 开播规律热力：星期（0=周日..6=周六）× 时段（0:0-5 1:6-11 2:12-17 3:18-23）
     * 的开播次数矩阵 [7][4]。只算已闭合场次；星期映射同 [favoriteWeekday]。
     */
    fun weekdayHourHeatmap(
        sessions: List<StreamSessionEntity>,
        localDayOffsetMs: Long = 0
    ): Array<IntArray> {
        val heat = Array(7) { IntArray(4) }
        sessions.filter { it.endTs != null && it.endTs!! > it.startTs }.forEach { s ->
            val localMs = s.startTs + localDayOffsetMs
            val epochDay = localMs / DAY_MS
            val weekday = ((epochDay + 4) % 7).toInt()
            val hour = ((localMs % DAY_MS) / 3_600_000L).toInt()
            heat[weekday][hour / 6]++
        }
        return heat
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
     * 本月逐日人气峰值（月度人气曲线数据源）：ts 落在当月的人气点按日聚合取 max，
     * 返回 (日, 峰值) 列表按日升序；无数据的日不出现在列表（折线跨空点直连）。
     */
    fun dailyPeakOnline(
        points: List<Pair<Long, Int>>, // (ts, online)
        monthStartMs: Long,
        daysInMonth: Int
    ): List<Pair<Int, Int>> {
        val peakByDay = mutableMapOf<Int, Int>()
        points.forEach { (ts, online) ->
            // floorDiv：月初前的负偏移必须落到 -1（普通整除向零截断会错归到 1 日）
            val dayOffset = Math.floorDiv(ts - monthStartMs, DAY_MS).toInt()
            if (dayOffset in 0 until daysInMonth) {
                val dom = dayOffset + 1
                peakByDay[dom] = maxOf(peakByDay[dom] ?: 0, online)
            }
        }
        return peakByDay.entries.sortedBy { it.key }.map { it.key to it.value }
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
