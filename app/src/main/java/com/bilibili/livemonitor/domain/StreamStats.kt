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
}
