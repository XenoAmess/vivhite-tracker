package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.FollowerSnapshotEntity
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 全量备份 ZIP 编解码。流式 API 用于生产路径，ByteArray API 仅保留给小数据和兼容调用。 */
object FullBackup {

    const val CURRENT_VERSION = 2
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_SESSIONS = "backup.csv"
    const val ENTRY_TITLE_CHANGES = "title_changes.csv"
    const val ENTRY_POPULARITY = "popularity.csv"
    const val ENTRY_FOLLOWERS = "followers.csv"
    const val ENTRY_PREFS = "prefs.json"
    const val COVERS_PREFIX = "covers/"

    private const val FORMAT_ID = "vivhite-full-backup"
    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_COVER_COUNT = 5_000
    private const val MAX_TEXT_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_COVER_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_EXPANDED_BYTES = 512L * 1024 * 1024

    class DamagedBackupException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    class IncompatibleBackupException(val version: Int) :
        IOException("备份版本 $version 与当前应用不兼容")

    data class TitleChangeRow(
        val sessionStart: Long,
        val sessionEnd: Long?,
        val changedAt: Long,
        val oldTitle: String?,
        val newTitle: String?
    )

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
        val covers: Map<String, ByteArray> = emptyMap(),
        /** Production imports spool covers here instead of retaining every image in memory. */
        val coverFiles: Map<String, File> = emptyMap(),
        val formatVersion: Int = CURRENT_VERSION
    ) {
        val coverNames: Set<String> get() = covers.keys + coverFiles.keys
    }

    data class RestoreCount(val added: Int = 0, val merged: Int = 0, val skipped: Int = 0)

    data class RestoreReport(
        val sessions: RestoreCount,
        val moods: RestoreCount,
        val titleChanges: RestoreCount,
        val popularity: RestoreCount,
        val followers: RestoreCount,
        val covers: RestoreCount,
        val preferencesRestored: Boolean,
        val magicPeriodsRestored: Boolean
    )

    fun pack(data: Data): ByteArray = ByteArrayOutputStream().also { pack(data, it) }.toByteArray()

    fun pack(data: Data, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun putText(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.writer(Charsets.UTF_8).apply { write(text); flush() }
                zip.closeEntry()
            }

            putText(
                ENTRY_MANIFEST,
                """{"format":"$FORMAT_ID","version":$CURRENT_VERSION}"""
            )
            putText(ENTRY_SESSIONS, SessionBackup.toCsv(data.sessions, data.moods))
            putText(ENTRY_TITLE_CHANGES, titleChangesCsv(data.titleChanges))
            putText(ENTRY_POPULARITY, popularityCsv(data.popularity))
            putText(ENTRY_FOLLOWERS, followersCsv(data.followers))
            data.prefsJson?.let { putText(ENTRY_PREFS, it) }

            val written = mutableSetOf<String>()
            data.covers.forEach { (name, bytes) ->
                validateCoverName(name)
                zip.putNextEntry(ZipEntry(COVERS_PREFIX + name))
                zip.write(bytes)
                zip.closeEntry()
                written += name
            }
            data.coverFiles.forEach { (name, file) ->
                validateCoverName(name)
                if (!written.add(name) || !file.isFile) return@forEach
                zip.putNextEntry(ZipEntry(COVERS_PREFIX + name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun titleChangesCsv(rows: List<TitleChangeRow>): String = buildString {
        append("session_start,session_end,changed_at,old_title,new_title\n")
        rows.forEach { r ->
            append(
                listOf(
                    r.sessionStart.toString(), r.sessionEnd?.toString() ?: "",
                    r.changedAt.toString(), SessionBackup.field(r.oldTitle ?: ""),
                    SessionBackup.field(r.newTitle ?: "")
                ).joinToString(",")
            ).append('\n')
        }
    }

    private fun popularityCsv(rows: List<PopularityRow>): String = buildString {
        append("session_start,session_end,ts,online\n")
        rows.forEach { append("${it.sessionStart},${it.sessionEnd ?: ""},${it.ts},${it.online}\n") }
    }

    private fun followersCsv(rows: List<FollowerSnapshotEntity>): String = buildString {
        append("ts,follower_num\n")
        rows.forEach { append("${it.ts},${it.followerNum}\n") }
    }

    fun unpack(bytes: ByteArray): Data = unpack(ByteArrayInputStream(bytes))

    /** If [coverDirectory] is supplied, cover entries are copied to files as they are read. */
    fun unpack(input: InputStream, coverDirectory: File? = null): Data {
        val source = if (input.markSupported()) input else BufferedInputStream(input)
        source.mark(4)
        val signature = ByteArray(4)
        val signatureSize = source.read(signature)
        source.reset()
        if (signatureSize < 4 || signature[0] != 'P'.code.toByte() || signature[1] != 'K'.code.toByte()) {
            throw DamagedBackupException("不是有效的备份 ZIP 文件")
        }

        var sessions = listOf<StreamSessionEntity>()
        var moods = listOf<MoodEventEntity>()
        var titleChanges = listOf<TitleChangeRow>()
        var popularity = listOf<PopularityRow>()
        var followers = listOf<FollowerSnapshotEntity>()
        var prefsJson: String? = null
        val covers = mutableMapOf<String, ByteArray>()
        val coverFiles = mutableMapOf<String, File>()
        var manifestVersion: Int? = null
        var sawSessions = false
        var sawTitleChanges = false
        var sawPopularity = false
        var sawFollowers = false
        var sawPrefs = false
        var entryCount = 0
        var coverCount = 0
        var totalExpandedBytes = 0L

        try {
            ZipInputStream(source).use { zip ->
                fun readLimited(maxBytes: Long): ByteArray {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalExpandedBytes += read
                        if (entryBytes > maxBytes || totalExpandedBytes > MAX_TOTAL_EXPANDED_BYTES) {
                            throw DamagedBackupException("备份条目过大")
                        }
                        out.write(buffer, 0, read)
                    }
                    return out.toByteArray()
                }

                fun copyLimited(output: OutputStream, maxBytes: Long) {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalExpandedBytes += read
                        if (entryBytes > maxBytes || totalExpandedBytes > MAX_TOTAL_EXPANDED_BYTES) {
                            throw DamagedBackupException("备份封面过大")
                        }
                        output.write(buffer, 0, read)
                    }
                }

                fun discardLimited(maxBytes: Long) {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalExpandedBytes += read
                        if (entryBytes > maxBytes || totalExpandedBytes > MAX_TOTAL_EXPANDED_BYTES) {
                            throw DamagedBackupException("未知备份条目过大")
                        }
                    }
                }

                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) throw DamagedBackupException("备份条目过多")
                    when (entry.name) {
                        ENTRY_MANIFEST -> {
                            if (manifestVersion != null) throw DamagedBackupException("备份清单重复")
                            manifestVersion = parseManifest(readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8))
                        }
                        ENTRY_SESSIONS -> {
                            if (sawSessions) throw DamagedBackupException("场次数据重复")
                            val text = readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8)
                            val first = SessionBackup.parseRecords(text).firstOrNull()?.firstOrNull()?.trim()
                            if (first != "类型" && first != "场次") {
                                throw DamagedBackupException("场次 CSV 表头无效")
                            }
                            val parsed = SessionBackup.parse(text)
                            sessions = parsed.sessions.map {
                                StreamSessionEntity(
                                    startTs = it.startTs, endTs = it.endTs,
                                    title = it.title, coverPath = it.coverName
                                )
                            }
                            moods = parsed.moods.map {
                                MoodEventEntity(
                                    eventTs = it.eventTs, durationMin = it.durationMin,
                                    mood = it.mood, title = it.title, reason = it.reason,
                                    note = it.note, createdAt = it.createdAt
                                )
                            }
                            sawSessions = true
                        }
                        ENTRY_TITLE_CHANGES -> {
                            if (sawTitleChanges) throw DamagedBackupException("主题变化数据重复")
                            titleChanges = parseTitleChanges(readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8))
                            sawTitleChanges = true
                        }
                        ENTRY_POPULARITY -> {
                            if (sawPopularity) throw DamagedBackupException("人气数据重复")
                            popularity = parsePopularity(readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8))
                            sawPopularity = true
                        }
                        ENTRY_FOLLOWERS -> {
                            if (sawFollowers) throw DamagedBackupException("粉丝数据重复")
                            followers = parseFollowers(readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8))
                            sawFollowers = true
                        }
                        ENTRY_PREFS -> {
                            if (sawPrefs) throw DamagedBackupException("设置数据重复")
                            prefsJson = readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8)
                            sawPrefs = true
                        }
                        else -> {
                            if (entry.name.startsWith(COVERS_PREFIX)) {
                                coverCount++
                                if (coverCount > MAX_COVER_COUNT) throw DamagedBackupException("封面数量过多")
                                val name = entry.name.removePrefix(COVERS_PREFIX)
                                validateCoverName(name)
                                if (name in covers || name in coverFiles) {
                                    throw DamagedBackupException("封面文件重复：$name")
                                }
                                if (coverDirectory == null) {
                                    covers[name] = readLimited(MAX_COVER_BYTES)
                                } else {
                                    if (!coverDirectory.exists() && !coverDirectory.mkdirs()) {
                                        throw IOException("无法创建封面临时目录")
                                    }
                                    val file = File(coverDirectory, name)
                                    file.outputStream().use { copyLimited(it, MAX_COVER_BYTES) }
                                    coverFiles[name] = file
                                }
                            } else {
                                discardLimited(MAX_TEXT_ENTRY_BYTES)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: IncompatibleBackupException) {
            throw e
        } catch (e: DamagedBackupException) {
            throw e
        } catch (e: ZipException) {
            throw DamagedBackupException("备份 ZIP 已损坏", e)
        } catch (e: Exception) {
            throw DamagedBackupException("无法读取备份 ZIP", e)
        }

        if (entryCount == 0 || !sawSessions) throw DamagedBackupException("备份 ZIP 缺少场次数据")
        val version = manifestVersion ?: 1
        if (version > CURRENT_VERSION || version < 1) throw IncompatibleBackupException(version)
        return Data(
            sessions, moods, titleChanges, popularity, followers, prefsJson,
            covers, coverFiles, version
        )
    }

    private fun parseManifest(text: String): Int {
        val format = Regex("\\\"format\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1)
        val version = Regex("\\\"version\\\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (format != FORMAT_ID || version == null) throw DamagedBackupException("备份清单无效")
        if (version > CURRENT_VERSION || version < 1) throw IncompatibleBackupException(version)
        return version
    }

    private fun validateCoverName(name: String) {
        if (name.isBlank() || name != File(name).name || '/' in name || '\\' in name) {
            throw DamagedBackupException("封面文件名无效")
        }
    }

    private fun parseTitleChanges(text: String): List<TitleChangeRow> {
        val records = validatedRecords(
            text,
            listOf("session_start", "session_end", "changed_at", "old_title", "new_title"),
            "主题变化"
        )
        return records.mapIndexed { index, r ->
            val start = r.getOrNull(0)?.toLongOrNull()
                ?: throw DamagedBackupException("主题变化第 ${index + 2} 行无效")
            val changedAt = r.getOrNull(2)?.toLongOrNull()
                ?: throw DamagedBackupException("主题变化第 ${index + 2} 行无效")
            TitleChangeRow(
                start, r.getOrNull(1)?.toLongOrNull(), changedAt,
                r.getOrNull(3)?.ifBlank { null }, r.getOrNull(4)?.ifBlank { null }
            )
        }
    }

    private fun parsePopularity(text: String): List<PopularityRow> {
        val records = validatedRecords(
            text,
            listOf("session_start", "session_end", "ts", "online"),
            "人气"
        )
        return records.mapIndexed { index, r ->
            val start = r.getOrNull(0)?.toLongOrNull()
                ?: throw DamagedBackupException("人气第 ${index + 2} 行无效")
            val ts = r.getOrNull(2)?.toLongOrNull()
                ?: throw DamagedBackupException("人气第 ${index + 2} 行无效")
            val online = r.getOrNull(3)?.toIntOrNull()
                ?: throw DamagedBackupException("人气第 ${index + 2} 行无效")
            PopularityRow(start, r.getOrNull(1)?.toLongOrNull(), ts, online)
        }
    }

    private fun parseFollowers(text: String): List<FollowerSnapshotEntity> {
        val records = validatedRecords(text, listOf("ts", "follower_num"), "粉丝")
        return records.mapIndexed { index, r ->
            val ts = r.getOrNull(0)?.toLongOrNull()
                ?: throw DamagedBackupException("粉丝第 ${index + 2} 行无效")
            val num = r.getOrNull(1)?.toLongOrNull()
                ?: throw DamagedBackupException("粉丝第 ${index + 2} 行无效")
            FollowerSnapshotEntity(ts = ts, followerNum = num)
        }
    }

    private fun validatedRecords(
        text: String,
        expectedHeader: List<String>,
        label: String
    ): List<List<String>> {
        val records = SessionBackup.parseRecords(text)
        if (records.isEmpty() || records.first().map { it.trim() } != expectedHeader) {
            throw DamagedBackupException("$label CSV 表头无效")
        }
        return records.drop(1).filter { row -> row.any { it.isNotBlank() } }
    }
}
