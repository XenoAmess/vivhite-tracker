package com.bilibili.livemonitor.domain

/**
 * 魔法期记录（纯数据 + 纯函数，无 Android 依赖，可纯 JVM 单测）。
 *
 * 业务语义（2026-08 需求）：
 * - 记录白绮的魔法期（开始/结束时间，可为过去或未来），默认一段 3 天
 * - 结束时间到 → 响铃 + 通知（排程用 [nextPendingEnd]）
 * - 分享图文案：最新一条未结束 →「死了啦，都怪你~」，否则 →「复活吧，我的爱人！」
 */
data class MagicPeriod(val start: Long, val end: Long)

object MagicPeriodDecider {

    const val DEFAULT_DURATION_DAYS = 3
    private const val DAY_MS = 24L * 3600 * 1000

    /** 分享图文案：最新一条魔法期还没结束 → 死了啦；否则 → 复活吧 */
    fun imageText(latestEnd: Long?, nowMs: Long): String {
        return if (latestEnd != null && latestEnd > nowMs) {
            "死了啦，都怪你~"
        } else {
            "复活吧，我的爱人！"
        }
    }

    /** 结束 ⇄ 时长联动：由开始 + 时长（天）算结束（精确到毫秒，72h/天） */
    fun computeEnd(startMs: Long, durationDays: Int): Long =
        startMs + durationDays.toLong() * DAY_MS

    /** 结束 ⇄ 时长联动：由开始 + 结束算时长（天，不足一天按一天进位，最小 1 天） */
    fun computeDurationDays(startMs: Long, endMs: Long): Int {
        val diff = endMs - startMs
        if (diff <= 0) return 1
        return ((diff + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)
    }

    /** 最近的未来结束时间（用于排闹钟）；无未来结束返回 null */
    fun nextPendingEnd(periods: List<MagicPeriod>, nowMs: Long): Long? =
        periods.map { it.end }.filter { it > nowMs }.minOrNull()

    /** 某段是否覆盖某一天（按当天的 00:00 ~ 次日 00:00 区间与段有交集判定） */
    fun coversDay(period: MagicPeriod, dayStartMs: Long): Boolean {
        val dayEnd = dayStartMs + DAY_MS
        return period.start < dayEnd && period.end > dayStartMs
    }

    /** 日期是否被任意已记录段覆盖（日历标记显示用） */
    fun isDayMarked(periods: List<MagicPeriod>, dayStartMs: Long): Boolean =
        periods.any { coversDay(it, dayStartMs) }

    /** 连续天长条的分段位置（日历圆角背景用）：孤日/段首/段中/段尾/未标记 */
    enum class SegmentPosition { ISOLATED, FIRST, MIDDLE, LAST, NONE }

    /**
     * 某天在连续长条中的位置：
     * - NONE：未被标记
     * - ISOLATED：前后一天都未被标记（单日段）
     * - FIRST：前一天未标记、后一天被标记（段首，左圆角）
     * - LAST：前一天被标记、后一天未标记（段尾，右圆角）
     * - MIDDLE：前后一天都被标记（段中，直角连接）
     */
    fun segmentPositionOf(periods: List<MagicPeriod>, dayStartMs: Long): SegmentPosition {
        if (!isDayMarked(periods, dayStartMs)) return SegmentPosition.NONE
        val prev = isDayMarked(periods, dayStartMs - DAY_MS)
        val next = isDayMarked(periods, dayStartMs + DAY_MS)
        return when {
            !prev && !next -> SegmentPosition.ISOLATED
            !prev && next -> SegmentPosition.FIRST
            prev && !next -> SegmentPosition.LAST
            else -> SegmentPosition.MIDDLE
        }
    }

    /**
     * 点选 toggle（单击选开始 +3 天 / 再点取消）：
     * - 该日已被某段覆盖 → 删除所有覆盖该日的段（取消整段）
     * - 未覆盖 → 新增 {dayStartMs, +DEFAULT_DURATION_DAYS 天}
     */
    fun toggleDay(periods: List<MagicPeriod>, dayStartMs: Long): List<MagicPeriod> {
        val covering = periods.filter { coversDay(it, dayStartMs) }
        return if (covering.isNotEmpty()) {
            periods - covering.toSet()
        } else {
            periods + MagicPeriod(dayStartMs, computeEnd(dayStartMs, DEFAULT_DURATION_DAYS))
        }
    }

    /** 更新某一条的开始（保持时长不变），返回新列表 */
    fun updateStart(periods: List<MagicPeriod>, index: Int, newStartMs: Long): List<MagicPeriod> {
        if (index !in periods.indices) return periods
        val old = periods[index]
        val duration = old.end - old.start
        return periods.toMutableList().apply { set(index, MagicPeriod(newStartMs, newStartMs + duration)) }
    }

    /** 更新某一条的时长（保持开始不变），返回新列表 */
    fun updateDuration(periods: List<MagicPeriod>, index: Int, durationDays: Int): List<MagicPeriod> {
        if (index !in periods.indices || durationDays < 1) return periods
        val old = periods[index]
        return periods.toMutableList().apply { set(index, MagicPeriod(old.start, computeEnd(old.start, durationDays))) }
    }

    /** 更新某一条的结束（开始不变、时长重算），返回新列表 */
    fun updateEnd(periods: List<MagicPeriod>, index: Int, newEndMs: Long): List<MagicPeriod> {
        if (index !in periods.indices) return periods
        val old = periods[index]
        val end = if (newEndMs > old.start) newEndMs else old.start + DAY_MS
        return periods.toMutableList().apply { set(index, MagicPeriod(old.start, end)) }
    }
}
