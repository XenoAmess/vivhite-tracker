package com.bilibili.livemonitor.util

import android.content.Context
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MediaSnapshotEntity
import com.bilibili.livemonitor.domain.MoodCatalog
import com.bilibili.livemonitor.domain.MoodTiming
import com.bilibili.livemonitor.domain.StreamStats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 海报数据组装（绮迹手账页导出与月初自动生成共用）：指定月份的完整
 * StatsImageData。纯数据组装（DB + prefs），渲染在 StatsImageRenderer。
 */
object StatsImageDataFactory {

    private const val DAY_MS = 86_400_000L
    private val WEEKDAY_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 以 monthCal 所在自然月为准组装 */
    suspend fun build(context: Context, monthCal: Calendar): StatsImageRenderer.StatsImageData {
        val db = AppDatabase.get(context)
        val now = System.currentTimeMillis()
        val localOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val monthStart = (monthCal.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val leading = (monthStart.get(Calendar.DAY_OF_WEEK) - 1)
        val daysInMonth = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)

        val monthSessions = db.streamSessionDao()
            .sessionsBetween(monthStart.timeInMillis, monthEnd.timeInMillis)
        val monthMoods = db.moodEventDao().eventsBetween(monthStart.timeInMillis, monthEnd.timeInMillis)
        val monthlyPopularityPoints = db.streamSessionDao()
            .popularityBetween(monthStart.timeInMillis, monthEnd.timeInMillis)
        val popularityBySession = db.streamSessionDao()
            .popularityForSessionsStartingBetween(monthStart.timeInMillis, monthEnd.timeInMillis)
            .groupBy { it.sessionId }
        val coverSnapshots = db.mediaSnapshotDao().snapshotsForSessionsStartingBetween(
            MediaSnapshotEntity.KIND_ROOM_COVER,
            monthStart.timeInMillis,
            monthEnd.timeInMillis
        ).groupBy { it.sessionStartTs }
        val titleChangesBySession = db.streamSessionDao()
            .titleChangesForSessionsStartingBetween(monthStart.timeInMillis, monthEnd.timeInMillis)
            .groupBy { it.sessionId }
        val followerPoints = db.streamSessionDao().followerSnapshots()
            .filter { it.ts >= monthStart.timeInMillis && it.ts < monthEnd.timeInMillis }
            .map { it.ts to it.followerNum.toInt() }

        val (monthCount, monthAvg, monthMax) = StreamStats.monthSummary(monthSessions)
        val validSessions = monthSessions.filter { it.endTs != null && it.endTs > it.startTs }
        val totalDuration = validSessions.sumOf { it.endTs!! - it.startTs }
        val activeDays = validSessions.map { session ->
            Calendar.getInstance().apply { timeInMillis = session.startTs }.get(Calendar.DAY_OF_YEAR)
        }.toSet().size
        val totalDurationText = if (totalDuration > 0) formatDuration(totalDuration) else "0分钟"
        val summaryLines = mutableListOf(
            "本月 $monthCount 场 · 共 $totalDurationText · 活跃 $activeDays 天",
            "平均 ${formatDuration(monthAvg)} · 最长 ${formatDuration(monthMax)}"
        )
        StreamStats.favoriteWeekday(monthSessions, localOffset)?.let {
            summaryLines += "常播：${WEEKDAY_NAMES[it.first]} · ${it.second} 场"
        }
        if (followerPoints.size >= 2) {
            val first = followerPoints.first().second
            val last = followerPoints.last().second
            val delta = last - first
            summaryLines += "粉丝 $first → $last · ${if (delta >= 0) "+" else ""}$delta"
        }

        val barCounts = StreamStats.weeklyCounts(monthSessions, monthStart.timeInMillis, daysInMonth)
        val barLabels = listOf("1-7", "8-14", "15-21", "22-28", "29-$daysInMonth")

        val dayOfMonth = { ts: Long ->
            Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.DAY_OF_MONTH)
        }
        val sessionDays = monthSessions.map { dayOfMonth(it.startTs) }.toSet()
        val todayDom = run {
            val t = Calendar.getInstance()
            if (t.get(Calendar.YEAR) == monthStart.get(Calendar.YEAR) &&
                t.get(Calendar.MONTH) == monthStart.get(Calendar.MONTH)
            ) {
                t.get(Calendar.DAY_OF_MONTH)
            } else {
                0
            }
        }

        val magicPeriods = MagicPeriodStore.load(PreferenceManager(context))
        val magicDays = (1..daysInMonth).filter { dom ->
            com.bilibili.livemonitor.domain.MagicPeriodDecider.isDayMarked(
                magicPeriods, monthStart.timeInMillis + (dom - 1) * DAY_MS
            )
        }.toSet()
        val magicSegments = com.bilibili.livemonitor.domain.MagicPeriodDecider.monthSegments(
            magicPeriods, monthStart.timeInMillis, daysInMonth
        )
        val magicSummary = if (magicSegments.isEmpty()) {
            null
        } else {
            val totalDays = magicSegments.sumOf { it.second - it.first + 1 }
            "本月魔法期：${magicSegments.size} 段 · 共 $totalDays 天"
        }

        val moodStats = monthMoods.groupingBy { MoodCatalog.display(it.mood) }
            .eachCount().entries.sortedByDescending { it.value }.map { it.toPair() }

        val dayFmt = SimpleDateFormat("MM-dd", Locale.getDefault())
        val records = mutableListOf<Pair<Long, StatsImageRenderer.RecordLine>>()
        monthSessions.forEach { s ->
            val time = timeFormat.format(Date(s.startTs)) +
                (s.endTs?.let { "~${timeFormat.format(Date(it))}" } ?: "~进行中")
            val duration = s.endTs?.let { " · ${formatDuration(it - s.startTs)}" } ?: ""
            val coverSnaps = coverSnapshots[s.startTs].orEmpty()
            records += s.startTs to StatsImageRenderer.RecordLine(
                kind = StatsImageRenderer.RecordKind.SESSION,
                text = "${dayFmt.format(Date(s.startTs))} $time$duration · ${s.title ?: "（无标题）"}",
                detailLines = buildList {
                    val titleChanges = titleChangesBySession[s.id].orEmpty()
                    if (titleChanges.isNotEmpty()) {
                        add(
                            "主题变化：" + titleChanges.joinToString("；") { tc ->
                                val at = timeFormat.format(Date(tc.changedAt))
                                val from = tc.oldTitle?.takeIf { it.isNotBlank() } ?: "开播"
                                val to = tc.newTitle?.takeIf { it.isNotBlank() } ?: "（空）"
                                "$at 「$from」→「$to」"
                            }
                        )
                    }
                    if (coverSnaps.size > 1) {
                        val times = coverSnaps.drop(1)
                            .joinToString("、") { timeFormat.format(Date(it.observedAt)) }
                        add("封面变化 ${coverSnaps.size - 1} 次（$times）")
                    }
                },
                popularityPoints = popularityBySession[s.id].orEmpty().map { it.ts to it.online },
                coverPaths = buildList {
                    s.coverPath?.takeIf { it.isNotBlank() }?.let(::add)
                    coverSnaps.forEach { snapshot ->
                        add(java.io.File(context.filesDir, "covers/${snapshot.fileName}").absolutePath)
                    }
                }.distinct()
            )
        }
        monthMoods.forEach { m ->
            val time = timeFormat.format(Date(m.eventTs)) +
                if (m.durationMin > 0) {
                    "~${timeFormat.format(Date(MoodTiming.endTs(m.eventTs, m.durationMin)))}"
                } else {
                    ""
                }
            val duration = m.durationMin.takeIf { it > 0 }?.let { " · $it 分钟" } ?: ""
            records += m.eventTs to StatsImageRenderer.RecordLine(
                kind = StatsImageRenderer.RecordKind.MOOD,
                text = "${dayFmt.format(Date(m.eventTs))} $time$duration ${MoodCatalog.display(m.mood)} · ${m.title}",
                detailLines = listOfNotNull(
                    m.reason?.takeIf { it.isNotBlank() }?.let { "原因：$it" },
                    m.note?.takeIf { it.isNotBlank() }?.let { "备注：$it" }
                )
            )
        }
        magicSegments.forEach { (startDom, endDom) ->
            val month = monthStart.get(Calendar.MONTH) + 1
            val rangeText = if (startDom == endDom) {
                "%02d-%02d".format(month, startDom)
            } else {
                "%02d-%02d ~ %02d-%02d".format(month, startDom, month, endDom)
            }
            records += monthStart.timeInMillis + (startDom - 1) * DAY_MS to
                StatsImageRenderer.RecordLine(
                    kind = StatsImageRenderer.RecordKind.MAGIC,
                    text = "$rangeText · 魔法期 ${endDom - startDom + 1} 天"
                )
        }

        return StatsImageRenderer.StatsImageData(
            monthTitle = SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(monthStart.time),
            summaryLines = summaryLines,
            barsTitle = "本月逐周场次",
            barCounts = barCounts,
            barLabels = barLabels,
            leading = leading,
            daysInMonth = daysInMonth,
            sessionDays = sessionDays,
            magicDays = magicDays,
            todayDom = todayDom,
            moodStats = moodStats,
            magicSummary = magicSummary,
            weekdayHeat = StreamStats.weekdayHourHeatmap(monthSessions, localOffset),
            followerPoints = followerPoints,
            dailyPopularity = StreamStats.dailyPeakOnline(
                monthlyPopularityPoints.map { it.ts to it.online },
                monthStart.timeInMillis, daysInMonth
            ),
            wordCloudWords = com.bilibili.livemonitor.domain.TitleWordCloud.topWords(
                monthSessions.mapNotNull { it.title } +
                    db.streamSessionDao().changeTitlesBetween(
                        monthStart.timeInMillis, monthEnd.timeInMillis
                    )
            ),
            records = records.sortedBy { it.first }.map { it.second },
            exportDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        )
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--"
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }
}
