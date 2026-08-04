package com.bilibili.livemonitor.domain

/**
 * 勿扰时段判定（纯函数）。时间以「距 0 点的分钟数」表示（0..1439）。
 * 支持跨午夜区间（start > end，如 23:00 → 07:00）。
 */
object QuietHoursDecider {

    /**
     * @param nowMinutes 当前时刻（0..1439）
     * @param startMinutes 勿扰开始（0..1439）
     * @param endMinutes 勿扰结束（0..1439）
     * @param enabled 总开关
     */
    fun isInQuietHours(
        nowMinutes: Int,
        startMinutes: Int,
        endMinutes: Int,
        enabled: Boolean
    ): Boolean {
        if (!enabled) return false
        if (startMinutes == endMinutes) return false // 空区间
        return if (startMinutes < endMinutes) {
            nowMinutes >= startMinutes && nowMinutes < endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }
}
