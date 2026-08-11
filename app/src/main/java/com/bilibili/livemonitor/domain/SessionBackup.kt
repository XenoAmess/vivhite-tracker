package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 场次 + 心情混合 CSV 备份编解码（纯函数，导入导出共用）。
 *
 * 新格式表头：类型,开始,结束,时长(分钟),标题,心情,原因,备注
 * - 场次行：类型=场次，心情/原因/备注留空；未闭合场次结束写「进行中」（导入时跳过）
 * - 心情行：类型=心情，时长 0 时结束/时长留空；心情存「😄开心」display，
 *   导入按 MoodCatalog 反查回 key（裸 key 也认，都不认识保留原文）
 *
 * 兼容旧格式（无类型列）：场次,开始,结束,时长(分钟),标题
 * （旧导出文件正文行只有 4 列，首列「场次」只在表头）
 */
object SessionBackup {

    const val TYPE_SESSION = "场次"
    const val TYPE_MOOD = "心情"
    const val IN_PROGRESS = "进行中"
    const val HEADER = "类型,开始,结束,时长(分钟),标题,心情,原因,备注"

    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm"

    data class SessionRow(val startTs: Long, val endTs: Long?, val title: String?)

    data class MoodRow(
        val eventTs: Long,
        val durationMin: Int,
        val mood: String,
        val title: String,
        val reason: String?,
        val note: String?
    )

    data class Parsed(
        val sessions: List<SessionRow>,
        val moods: List<MoodRow>,
        val skippedLines: Int
    )

    // ==================== 导出 ====================

    fun toCsv(sessions: List<StreamSessionEntity>, moods: List<MoodEventEntity>): String {
        val fmt = SimpleDateFormat(DATE_PATTERN, Locale.getDefault())
        val rows = mutableListOf<Pair<Long, String>>()
        sessions.forEach { s ->
            val start = fmt.format(Date(s.startTs))
            val end = s.endTs?.let { fmt.format(Date(it)) } ?: IN_PROGRESS
            val minutes = s.endTs?.let { ((it - s.startTs) / 60_000L).toString() } ?: ""
            rows += s.startTs to listOf(
                TYPE_SESSION, start, end, minutes, s.title ?: "", "", "", ""
            ).joinToString(",") { field(it) }
        }
        moods.forEach { m ->
            val start = fmt.format(Date(m.eventTs))
            val end = if (m.durationMin > 0) {
                fmt.format(Date(MoodTiming.endTs(m.eventTs, m.durationMin)))
            } else {
                ""
            }
            val duration = if (m.durationMin > 0) m.durationMin.toString() else ""
            rows += m.eventTs to listOf(
                TYPE_MOOD, start, end, duration, m.title,
                MoodCatalog.display(m.mood), m.reason ?: "", m.note ?: ""
            ).joinToString(",") { field(it) }
        }
        return buildString {
            append(HEADER).append('\n')
            rows.sortedBy { it.first }.forEach { append(it.second).append('\n') }
        }
    }

    private fun field(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    // ==================== 导入 ====================

    fun parse(text: String): Parsed {
        val records = parseRecords(text).filter { record -> record.any { it.isNotBlank() } }
        if (records.isEmpty()) return Parsed(emptyList(), emptyList(), 0)
        val header = records.first()
        val isNewFormat = header[0].trim() == "类型"
        val body = records.drop(1)
        val fmt = SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).apply { isLenient = false }
        val sessions = mutableListOf<SessionRow>()
        val moods = mutableListOf<MoodRow>()
        var skipped = 0

        fun parseTs(value: String): Long? = runCatching {
            fmt.parse(value.trim())?.time
        }.getOrNull()

        for (record in body) {
            if (isNewFormat) {
                when (record[0].trim()) {
                    TYPE_SESSION -> {
                        val start = record.getOrNull(1)?.let { parseTs(it) }
                        val endRaw = record.getOrNull(2)?.trim().orEmpty()
                        if (start == null || endRaw == IN_PROGRESS) {
                            skipped++
                            continue
                        }
                        val end = if (endRaw.isEmpty()) null else parseTs(endRaw)
                        if (endRaw.isNotEmpty() && end == null) {
                            skipped++
                            continue
                        }
                        sessions += SessionRow(
                            startTs = start, endTs = end,
                            title = record.getOrNull(4)?.ifBlank { null }
                        )
                    }
                    TYPE_MOOD -> {
                        val start = record.getOrNull(1)?.let { parseTs(it) }
                        val title = record.getOrNull(4)?.trim().orEmpty()
                        if (start == null || title.isEmpty()) {
                            skipped++
                            continue
                        }
                        val duration = record.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
                        val moodDisplay = record.getOrNull(5)?.trim().orEmpty()
                        moods += MoodRow(
                            eventTs = start, durationMin = duration,
                            mood = MoodCatalog.keyOf(moodDisplay), title = title,
                            reason = record.getOrNull(6)?.ifBlank { null },
                            note = record.getOrNull(7)?.ifBlank { null }
                        )
                    }
                    else -> skipped++
                }
            } else {
                // 旧格式：数据行 4 列（开始,结束,时长,标题）；
                // 兼容手工补齐首列「场次」的 5 列变体
                val offset = if (record.size >= 5 && record[0].trim() == TYPE_SESSION) 1 else 0
                val start = record.getOrNull(offset)?.let { parseTs(it) }
                val endRaw = record.getOrNull(offset + 1)?.trim().orEmpty()
                if (start == null || endRaw == IN_PROGRESS) {
                    skipped++
                    continue
                }
                val end = if (endRaw.isEmpty()) null else parseTs(endRaw)
                if (endRaw.isNotEmpty() && end == null) {
                    skipped++
                    continue
                }
                sessions += SessionRow(
                    startTs = start, endTs = end,
                    title = record.getOrNull(offset + 3)?.ifBlank { null }
                )
            }
        }
        return Parsed(sessions, moods, skipped)
    }

    /**
     * 迷你 CSV 记录解析：引号包裹字段（"" 转义引号），字段内可含换行/逗号。
     * 返回记录列表，每条记录 = 字段列表（不做 trim，交由上层）。
     */
    internal fun parseRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' && field.isEmpty() -> inQuotes = true
                ch == ',' -> {
                    current += field.toString(); field.clear()
                }
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    current += field.toString(); field.clear()
                    records += current.toList(); current.clear()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) {
            current += field.toString()
            records += current.toList()
        }
        return records
    }
}
