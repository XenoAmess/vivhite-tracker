package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.StreamSessionEntity

/**
 * 桌面小组件附加行文案（纯函数）：「今日已播 XhYm · 上次开播 …」。
 * 两部分都无数据时返回 null（调用方隐藏该行）。
 */
object WidgetExtraDecider {

    /**
     * @param sessions 最近场次（含开放行）
     * @param now 当前时间
     */
    fun extraLine(sessions: List<StreamSessionEntity>, now: Long): String? {
        val parts = mutableListOf<String>()

        // 今日已播：今天（本地零点起）开始的已闭合场次时长和 + 进行中部分的已播时长
        val todayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        var todayMs = 0L
        sessions.forEach { s ->
            if (s.startTs >= todayStart) {
                val end = s.endTs ?: now
                if (end > s.startTs) todayMs += end - s.startTs
            }
        }
        if (todayMs >= 60_000L) {
            val h = todayMs / 3_600_000
            val m = todayMs % 3_600_000 / 60_000
            parts += "今日已播 " + (if (h > 0) "${h}小时${m}分" else "${m}分钟")
        }

        // 上次开播：最近一场（含进行中）距 now 的粗略间隔
        val latest = sessions.maxByOrNull { it.startTs }
        if (latest != null) {
            val gap = now - latest.startTs
            val live = latest.endTs == null
            if (!live && gap > 0) {
                val hours = gap / 3_600_000
                val days = gap / 86_400_000
                parts += when {
                    hours < 1 -> "上次开播 1 小时内"
                    hours < 24 -> "上次开播 ${hours} 小时前"
                    else -> "上次开播 ${days} 天前"
                }
            }
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
