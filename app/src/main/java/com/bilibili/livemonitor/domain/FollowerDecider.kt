package com.bilibili.livemonitor.domain

/**
 * 粉丝数快照天闸（纯函数）：距上次快照满 [minIntervalMs]（默认 20h）才再采，
 * 防 60s 检测循环每次都打接口。
 */
object FollowerDecider {

    const val SNAPSHOT_MIN_INTERVAL_MS = 20L * 3_600_000

    fun shouldSnapshot(lastSnapshotTs: Long?, now: Long, minIntervalMs: Long = SNAPSHOT_MIN_INTERVAL_MS): Boolean {
        if (lastSnapshotTs == null) return true
        return now - lastSnapshotTs >= minIntervalMs
    }
}
