package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.FollowerSnapshotEntity
import com.bilibili.livemonitor.db.MediaSnapshotEntity
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

    const val CURRENT_VERSION = 4
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_SESSIONS = "backup.csv"
    const val ENTRY_TITLE_CHANGES = "title_changes.csv"
    const val ENTRY_POPULARITY = "popularity.csv"
    const val ENTRY_FOLLOWERS = "followers.csv"
    const val ENTRY_MEDIA_SNAPSHOTS = "media_snapshots.csv"
    const val ENTRY_PREFS = "prefs.json"
    const val ENTRY_LOG = "logs/monitor.log"
    const val COVERS_PREFIX = "covers/"
    const val AVATARS_PREFIX = "avatars/"
    const val POSTERS_PREFIX = "posters/"

    private const val FORMAT_ID = "vivhite-full-backup"
    private const val MAX_ENTRY_COUNT = 15_010
    private const val MAX_COVER_COUNT = 5_000
    private const val MAX_AVATAR_COUNT = 10_000
    private const val MAX_POSTER_COUNT = 240
    private const val MAX_TEXT_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_COVER_BYTES = 32L * 1024 * 1024
    private const val MAX_AVATAR_BYTES = 32L * 1024 * 1024
    private const val MAX_POSTER_BYTES = 32L * 1024 * 1024
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
        val mediaSnapshots: List<MediaSnapshotEntity> = emptyList(),
        val avatars: Map<String, ByteArray> = emptyMap(),
        /** Production imports spool avatars here instead of retaining every image in memory. */
        val avatarFiles: Map<String, File> = emptyMap(),
        val posters: Map<String, ByteArray> = emptyMap(),
        /** Production imports spool posters here instead of retaining every image in memory. */
        val posterFiles: Map<String, File> = emptyMap(),
        /** 运行日志（monitor.log 全文，≤16MB）；v4 新增，可选 */
        val logBytes: ByteArray? = null,
        val formatVersion: Int = CURRENT_VERSION
    ) {
        val coverNames: Set<String> get() = covers.keys + coverFiles.keys
        val avatarNames: Set<String> get() = avatars.keys + avatarFiles.keys
        val posterNames: Set<String> get() = posters.keys + posterFiles.keys
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
        val magicPeriodsRestored: Boolean,
        val mediaSnapshots: RestoreCount = RestoreCount(),
        val avatars: RestoreCount = RestoreCount(),
        val posters: RestoreCount = RestoreCount(),
        val logRestored: Boolean = false
    )

    fun pack(data: Data): ByteArray = ByteArrayOutputStream().also { pack(data, it) }.toByteArray()

    fun pack(data: Data, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            val budget = WriteBudget()
            fun putText(name: String, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                budget.claim(bytes.size.toLong(), MAX_TEXT_ENTRY_BYTES)
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
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
            putText(ENTRY_MEDIA_SNAPSHOTS, mediaSnapshotsCsv(data.mediaSnapshots))
            data.prefsJson?.let { putText(ENTRY_PREFS, it) }

            writeImages(
                zip, COVERS_PREFIX, data.covers, data.coverFiles, "封面",
                MAX_COVER_COUNT, MAX_COVER_BYTES, budget
            )
            writeImages(
                zip, AVATARS_PREFIX, data.avatars, data.avatarFiles, "头像",
                MAX_AVATAR_COUNT, MAX_AVATAR_BYTES, budget
            )
            writeImages(
                zip, POSTERS_PREFIX, data.posters, data.posterFiles, "海报",
                MAX_POSTER_COUNT, MAX_POSTER_BYTES, budget
            )
            data.logBytes?.let { bytes ->
                budget.claim(bytes.size.toLong(), MAX_TEXT_ENTRY_BYTES)
                zip.putNextEntry(ZipEntry(ENTRY_LOG))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun writeImages(
        zip: ZipOutputStream,
        prefix: String,
        inMemory: Map<String, ByteArray>,
        files: Map<String, File>,
        label: String,
        maxCount: Int,
        maxBytes: Long,
        budget: WriteBudget
    ) {
        if ((inMemory.keys + files.keys).size > maxCount) {
            throw DamagedBackupException("${label}数量过多")
        }
        val written = mutableSetOf<String>()
        inMemory.forEach { (name, bytes) ->
            validateImageName(name, label)
            budget.claim(bytes.size.toLong(), maxBytes)
            zip.putNextEntry(ZipEntry(prefix + name))
            zip.write(bytes)
            zip.closeEntry()
            written += name
        }
        files.forEach { (name, file) ->
            validateImageName(name, label)
            if (!written.add(name) || !file.isFile) return@forEach
            budget.claim(file.length(), maxBytes)
            zip.putNextEntry(ZipEntry(prefix + name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private class WriteBudget {
        private var entryCount = 0
        private var totalBytes = 0L

        fun claim(size: Long, entryLimit: Long) {
            entryCount++
            totalBytes += size
            if (entryCount > MAX_ENTRY_COUNT) throw DamagedBackupException("备份条目过多")
            if (size < 0 || size > entryLimit || totalBytes > MAX_TOTAL_EXPANDED_BYTES) {
                throw DamagedBackupException("备份条目过大")
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

    private fun mediaSnapshotsCsv(rows: List<MediaSnapshotEntity>): String = buildString {
        append("kind,observed_at,content_key,source_url,file_name,session_start_ts,title\n")
        rows.forEach { row ->
            append(
                listOf(
                    SessionBackup.field(row.kind),
                    row.observedAt.toString(),
                    SessionBackup.field(row.contentKey),
                    SessionBackup.field(row.sourceUrl ?: ""),
                    SessionBackup.field(row.fileName),
                    row.sessionStartTs?.toString() ?: "",
                    SessionBackup.field(row.title ?: "")
                ).joinToString(",")
            ).append('\n')
        }
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
        var mediaSnapshots = listOf<MediaSnapshotEntity>()
        var prefsJson: String? = null
        val covers = mutableMapOf<String, ByteArray>()
        val coverFiles = mutableMapOf<String, File>()
        val avatars = mutableMapOf<String, ByteArray>()
        val avatarFiles = mutableMapOf<String, File>()
        val posters = mutableMapOf<String, ByteArray>()
        val posterFiles = mutableMapOf<String, File>()
        var logBytes: ByteArray? = null
        var manifestVersion: Int? = null
        var sawSessions = false
        var sawTitleChanges = false
        var sawPopularity = false
        var sawFollowers = false
        var sawMediaSnapshots = false
        var sawPrefs = false
        var sawLog = false
        var entryCount = 0
        var coverCount = 0
        var avatarCount = 0
        var posterCount = 0
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
                    validateEntryPath(entry.name)
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
                        ENTRY_MEDIA_SNAPSHOTS -> {
                            if (sawMediaSnapshots) throw DamagedBackupException("媒体快照数据重复")
                            mediaSnapshots = parseMediaSnapshots(
                                readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8)
                            )
                            sawMediaSnapshots = true
                        }
                        ENTRY_PREFS -> {
                            if (sawPrefs) throw DamagedBackupException("设置数据重复")
                            prefsJson = readLimited(MAX_TEXT_ENTRY_BYTES).toString(Charsets.UTF_8)
                            sawPrefs = true
                        }
                        ENTRY_LOG -> {
                            if (sawLog) throw DamagedBackupException("日志数据重复")
                            logBytes = readLimited(MAX_TEXT_ENTRY_BYTES)
                            sawLog = true
                        }
                        else -> {
                            if (entry.name.startsWith(COVERS_PREFIX)) {
                                coverCount++
                                if (coverCount > MAX_COVER_COUNT) throw DamagedBackupException("封面数量过多")
                                val name = entry.name.removePrefix(COVERS_PREFIX)
                                validateImageName(name, "封面")
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
                            } else if (entry.name.startsWith(AVATARS_PREFIX)) {
                                avatarCount++
                                if (avatarCount > MAX_AVATAR_COUNT) throw DamagedBackupException("头像数量过多")
                                val name = entry.name.removePrefix(AVATARS_PREFIX)
                                validateImageName(name, "头像")
                                if (name in avatars || name in avatarFiles) {
                                    throw DamagedBackupException("头像文件重复：$name")
                                }
                                if (coverDirectory == null) {
                                    avatars[name] = readLimited(MAX_AVATAR_BYTES)
                                } else {
                                    val avatarDirectory = File(coverDirectory, "avatars")
                                    if (!avatarDirectory.exists() && !avatarDirectory.mkdirs()) {
                                        throw IOException("无法创建头像临时目录")
                                    }
                                    val file = File(avatarDirectory, name)
                                    file.outputStream().use { copyLimited(it, MAX_AVATAR_BYTES) }
                                    avatarFiles[name] = file
                                }
                            } else if (entry.name.startsWith(POSTERS_PREFIX)) {
                                posterCount++
                                if (posterCount > MAX_POSTER_COUNT) throw DamagedBackupException("海报数量过多")
                                val name = entry.name.removePrefix(POSTERS_PREFIX)
                                validateImageName(name, "海报")
                                if (name in posters || name in posterFiles) {
                                    throw DamagedBackupException("海报文件重复：$name")
                                }
                                if (coverDirectory == null) {
                                    posters[name] = readLimited(MAX_POSTER_BYTES)
                                } else {
                                    val posterDirectory = File(coverDirectory, "posters")
                                    if (!posterDirectory.exists() && !posterDirectory.mkdirs()) {
                                        throw IOException("无法创建海报临时目录")
                                    }
                                    val file = File(posterDirectory, name)
                                    file.outputStream().use { copyLimited(it, MAX_POSTER_BYTES) }
                                    posterFiles[name] = file
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
        if (version >= 3 && !sawMediaSnapshots) {
            throw DamagedBackupException("备份 ZIP 缺少媒体快照数据")
        }
        return Data(
            sessions = sessions,
            moods = moods,
            titleChanges = titleChanges,
            popularity = popularity,
            followers = followers,
            prefsJson = prefsJson,
            covers = covers,
            coverFiles = coverFiles,
            mediaSnapshots = mediaSnapshots,
            avatars = avatars,
            avatarFiles = avatarFiles,
            posters = posters,
            posterFiles = posterFiles,
            logBytes = logBytes,
            formatVersion = version
        )
    }

    private fun parseManifest(text: String): Int {
        val format = Regex("\\\"format\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1)
        val version = Regex("\\\"version\\\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (format != FORMAT_ID || version == null) throw DamagedBackupException("备份清单无效")
        if (version > CURRENT_VERSION || version < 1) throw IncompatibleBackupException(version)
        return version
    }

    private fun validateEntryPath(name: String) {
        if (name.isBlank() || name.startsWith('/') || name.startsWith('\\') || '\\' in name ||
            name.any { it.code == 0 || it.isISOControl() } ||
            name.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw DamagedBackupException("备份条目路径无效")
        }
    }

    private fun validateImageName(name: String, label: String) {
        if (name.isBlank() || name == "." || name == ".." || name != File(name).name ||
            '/' in name || '\\' in name || name.any { it.code == 0 || it.isISOControl() }
        ) {
            throw DamagedBackupException("${label}文件名无效")
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

    private fun parseMediaSnapshots(text: String): List<MediaSnapshotEntity> {
        val records = validatedRecords(
            text,
            listOf(
                "kind", "observed_at", "content_key", "source_url", "file_name",
                "session_start_ts", "title"
            ),
            "媒体快照"
        )
        return records.mapIndexed { index, row ->
            val line = index + 2
            val kind = row.getOrNull(0).orEmpty()
            val observedAt = row.getOrNull(1)?.toLongOrNull()
                ?: throw DamagedBackupException("媒体快照第 $line 行无效")
            val contentKey = row.getOrNull(2).orEmpty()
            val fileName = row.getOrNull(4).orEmpty()
            if (kind !in setOf(MediaSnapshotEntity.KIND_AVATAR, MediaSnapshotEntity.KIND_ROOM_COVER) ||
                contentKey.isBlank() || observedAt < 0
            ) {
                throw DamagedBackupException("媒体快照第 $line 行无效")
            }
            validateImageName(
                fileName,
                if (kind == MediaSnapshotEntity.KIND_AVATAR) "头像" else "封面"
            )
            val sessionStartText = row.getOrNull(5).orEmpty()
            val sessionStartTs = sessionStartText.toLongOrNull()
            if (sessionStartText.isNotBlank() && sessionStartTs == null) {
                throw DamagedBackupException("媒体快照第 $line 行无效")
            }
            MediaSnapshotEntity(
                id = 0,
                kind = kind,
                observedAt = observedAt,
                contentKey = contentKey,
                sourceUrl = row.getOrNull(3)?.ifBlank { null },
                fileName = fileName,
                sessionStartTs = sessionStartTs,
                title = row.getOrNull(6)?.ifBlank { null }
            )
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
