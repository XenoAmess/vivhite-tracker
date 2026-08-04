package com.bilibili.livemonitor.domain

/**
 * 开播预告提醒判定（纯函数）。
 * 预告开播时间在 (now, now+24h] 且该预告尚未提醒过 → 提醒一次。
 */
object LiveReminderDecider {

    const val WINDOW_MS = 24L * 3_600_000

    /**
     * @param liveStartMs 预告开播时间（ms），解析不到为 null
     * @param now 当前时间（ms）
     * @param lastRemindedId 上次已提醒的预告动态 id（按 id_str 去重）
     * @param dynamicId 本次预告动态 id
     */
    fun shouldRemind(
        liveStartMs: Long?,
        now: Long,
        lastRemindedId: String?,
        dynamicId: String
    ): Boolean {
        if (liveStartMs == null) return false
        if (dynamicId == lastRemindedId) return false
        return liveStartMs > now && liveStartMs <= now + WINDOW_MS
    }
}
