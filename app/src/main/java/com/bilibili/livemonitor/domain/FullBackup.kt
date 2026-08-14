package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.FollowerSnapshotEntity
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全量备份 ZIP 编解码（纯函数，java.util.zip，纯 JVM 可测）。
 *
 * 结构：
 *   backup.csv         场次+心情（复用 SessionBackup 混合格式，老导入路径兼容）
 *   title_changes.csv  主题变化（按 session 起止时间引用，不依赖自增 id）
 *   popularity.csv     人气点（同上）
 *   followers.csv      粉丝快照
 *   prefs.json         魔法期记录 + 设置项快照（PreferenceManager.exportSnapshot）
 *   covers/xxx.jpg     封面原图全量（文件名即 URL 的 sha256）
 */
object FullBackup {

    const val ENTRY_SESSIONS = "backup.csv"
    const val ENTRY_TITLE_CHANGES = "title_changes.csv"
    const val ENTRY_POPULARITY = "popularity.csv"
    const val ENTRY_FOLLOWERS = "followers.csv"
    const val ENTRY_PREFS = "prefs.json"
    const val COVERS_PREFIX = "covers/"

    /** 主题变化按场次起止时间引用（导入时映射到新 id） */
    data class TitleChangeRow(
        val sessionStart: Long,
        val sessionEnd: Long?,
        val changedAt: Long,
        val oldTitle: String?,
        val newTitle: String?
    )

    /** 人气点按场次起止时间引用（同上） */
    data class PopularityRow(
        val sessionStart: Long,
        val sessionEnd: Long?,
        val ts: Long,
        val online: Int
    )

    data class Data(
        val sessions: List<StreamSessionEntity>,
        val moods: List<MoodEventEntity>,
        val titleChanges: List<TitleChangeRow>,
        val popularity: List<PopularityRow>,
        val followers: List<FollowerSnapshotEntity>,
        val prefsJson: String?,
        val covers: Map<String, ByteArray>
    )

    // ==================== 打包 ====================

    fun pack(data: Data): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(ENTRY_SESSIONS, SessionBackup.toCsv(data.sessions, data.moods).toByteArray(Charsets.UTF_8))
            put(ENTRY_TITLE_CHANGES, titleChangesCsv(data.titleChanges).toByteArray(Charsets.UTF_8))
            put(ENTRY_POPULARITY, popularityCsv(data.popularity).toByteArray(Charsets.UTF_8))
            put(ENTRY_FOLLOWERS, followersCsv(data.followers).toByteArray(Charsets.UTF_8))
            data.prefsJson?.let { put(ENTRY_PREFS, it.toByteArray(Charsets.UTF_8)) }
            data.covers.forEach { (name, bytes) -> put(COVERS_PREFIX + name, bytes) }
        }
        return out.toByteArray()
    }

    private fun titleChangesCsv(rows: List<TitleChangeRow>): String {
        val b = StringBuilder("session_start,session_end,changed_at,old_title,new_title\n")
        rows.forEach { r ->
            b.append(
                listOf(
                    r.sessionStart.toString(),
                    r.sessionEnd?.toString() ?: "",
                    r.changedAt.toString(),
                    SessionBackup.field(r.oldTitle ?: ""),
                    SessionBackup.field(r.newTitle ?: "")
                ).joinToString(",")
            ).append('\n')
        }
        return b.toString()
    }

    private fun popularityCsv(rows: List<PopularityRow>): String {
        val b = StringBuilder("session_start,session_end,ts,online\n")
        rows.forEach { r ->
            b.append(
                "${r.sessionStart},${r.sessionEnd ?: ""},${r.ts},${r.online}"
            ).append('\n')
        }
        return b.toString()
    }

    private fun followersCsv(rows: List<FollowerSnapshotEntity>): String {
        val b = StringBuilder("ts,follower_num\n")
        rows.forEach { b.append("${it.ts},${it.followerNum}").append('\n') }
        return b.toString()
    }

    // ==================== 解包 ====================

    fun unpack(bytes: ByteArray): Data {
        var sessions = listOf<StreamSessionEntity>()
        var moods = listOf<MoodEventEntity>()
        var titleChanges = listOf<TitleChangeRow>()
        var popularity = listOf<PopularityRow>()
        var followers = listOf<FollowerSnapshotEntity>()
        var prefsJson: String? = null
        val covers = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes()
                when (entry.name) {
                    ENTRY_SESSIONS -> {
                        val parsed = SessionBackup.parse(String(content, Charsets.UTF_8))
                        sessions = parsed.sessions.map {
                            // coverPath 存的是 hash 文件名（basename），导入侧拼回 filesDir/covers/
                            StreamSessionEntity(
                                startTs = it.startTs, endTs = it.endTs,
                                title = it.title, coverPath = it.coverName
                            )
                        }
                        moods = parsed.moods.map {
                            MoodEventEntity(
                                eventTs = it.eventTs, durationMin = it.durationMin,
                                mood = it.mood, title = it.title, reason = it.reason,
                                note = it.note, createdAt = it.eventTs
                            )
                        }
                    }
                    ENTRY_TITLE_CHANGES -> titleChanges = parseTitleChanges(String(content, Charsets.UTF_8))
                    ENTRY_POPULARITY -> popularity = parsePopularity(String(content, Charsets.UTF_8))
                    ENTRY_FOLLOWERS -> followers = parseFollowers(String(content, Charsets.UTF_8))
                    ENTRY_PREFS -> prefsJson = String(content, Charsets.UTF_8)
                    else -> if (entry.name.startsWith(COVERS_PREFIX)) {
                        covers[entry.name.removePrefix(COVERS_PREFIX)] = content
                    }
                }
            }
        }
        return Data(sessions, moods, titleChanges, popularity, followers, prefsJson, covers)
    }

    private fun parseTitleChanges(text: String): List<TitleChangeRow> {
        val records = SessionBackup.parseRecords(text)
        return records.drop(1).mapNotNull { r ->
            val start = r.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val end = r.getOrNull(1)?.toLongOrNull()
            val changedAt = r.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            TitleChangeRow(
                sessionStart = start, sessionEnd = end, changedAt = changedAt,
                oldTitle = r.getOrNull(3)?.ifBlank { null },
                newTitle = r.getOrNull(4)?.ifBlank { null }
            )
        }
    }

    private fun parsePopularity(text: String): List<PopularityRow> {
        val records = SessionBackup.parseRecords(text)
        return records.drop(1).mapNotNull { r ->
            val start = r.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val end = r.getOrNull(1)?.toLongOrNull()
            val ts = r.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            val online = r.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
            PopularityRow(start, end, ts, online)
        }
    }

    private fun parseFollowers(text: String): List<FollowerSnapshotEntity> {
        val records = SessionBackup.parseRecords(text)
        return records.drop(1).mapNotNull { r ->
            val ts = r.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val num = r.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            FollowerSnapshotEntity(ts = ts, followerNum = num)
        }
    }
}
