package com.bilibili.livemonitor.domain

/**
 * 监控健康度（纯函数）：环形缓冲里的检测记录 → 近 24h 汇总。
 * 记录由 LiveCheckService 每次检测写入（PreferenceManager 环形 JSON）。
 */
object MonitorHealth {

    data class CheckRecord(
        val ts: Long,
        val success: Boolean,
        val isLive: Boolean,
        val reason: String
    )

    data class HealthSummary(
        val totalChecks: Int,
        val successChecks: Int,
        val liveChecks: Int,
        val topReasons: List<Pair<String, Int>>,   // 失败原因 top3（含次数）
        val avgIntervalMs: Long,                   // 实际平均间隔
        val lastCheckTs: Long?
    )

    /** 近 24h（now 往前）的检测汇总；无记录时全零 */
    fun summarize(records: List<CheckRecord>, now: Long, windowMs: Long = 86_400_000L): HealthSummary {
        val inWindow = records.filter { it.ts >= now - windowMs }.sortedBy { it.ts }
        val success = inWindow.count { it.success }
        val failed = inWindow.filter { !it.success }
        val topReasons = failed.groupingBy { it.reason }.eachCount()
            .entries.sortedByDescending { it.value }.take(3).map { it.toPair() }
        val intervals = inWindow.zipWithNext().map { (a, b) -> b.ts - a.ts }
        val avgInterval = if (intervals.isEmpty()) 0L else intervals.sum() / intervals.size
        return HealthSummary(
            totalChecks = inWindow.size,
            successChecks = success,
            liveChecks = inWindow.count { it.isLive },
            topReasons = topReasons,
            avgIntervalMs = avgInterval,
            lastCheckTs = inWindow.lastOrNull()?.ts
        )
    }
}
