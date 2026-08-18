package com.bilibili.livemonitor.controller

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.PopularityPointEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.db.StreamTitleChangeEntity
import com.bilibili.livemonitor.domain.FullBackup
import com.bilibili.livemonitor.util.AppUpdater
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.worker.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class BackupRestoreCoordinator(
    private val context: Context,
    private val database: AppDatabase
) {

    suspend fun restore(data: FullBackup.Data): FullBackup.RestoreReport = withContext(Dispatchers.IO) {
        val sessionDao = database.streamSessionDao()
        val moodDao = database.moodEventDao()

        // Covers are staged first. A failed DB transaction can leave only harmless unreferenced files.
        var coverAdded = 0
        var coverSkipped = 0
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        data.coverNames.forEach { name ->
            val destination = File(coversDir, name)
            if (isValidImageFile(destination)) {
                coverSkipped++
            } else {
                destination.delete()
                val temp = File.createTempFile(".${name}.", ".part", coversDir)
                try {
                    temp.outputStream().use { output ->
                        data.covers[name]?.let { output.write(it) }
                            ?: data.coverFiles[name]?.inputStream()?.use { input -> input.copyTo(output) }
                            ?: throw IOException("缺少封面数据：$name")
                        output.flush()
                        (output as? FileOutputStream)?.fd?.sync()
                    }
                    if (!isValidImageFile(temp) || !AppUpdater.publishAtomically(temp, destination)) {
                        throw IOException("封面文件无效：$name")
                    }
                } finally {
                    temp.delete()
                }
                coverAdded++
            }
        }

        var sessionAdded = 0
        var sessionMerged = 0
        var sessionSkipped = 0
        var moodAdded = 0
        var moodMerged = 0
        var moodSkipped = 0
        var titleAdded = 0
        var titleMerged = 0
        var titleSkipped = 0
        var popularityAdded = 0
        var popularitySkipped = 0
        var followerAdded = 0
        var followerSkipped = 0
        database.withTransaction {
            val restoredSessions = mutableMapOf<Pair<Long, Long?>, StreamSessionEntity>()
            sessionDao.findOpenSession()?.let { newest ->
                sessionDao.closeOtherOpenSessions(newest.id, newest.startTs)
            }
            data.sessions.sortedBy { it.startTs }.forEach { incoming ->
                require(incoming.endTs == null || incoming.endTs >= incoming.startTs) {
                    "场次结束时间早于开始时间"
                }
                val coverPath = incoming.coverPath?.substringAfterLast('/')?.let { name ->
                    File(coversDir, name).takeIf { it.isFile }?.absolutePath
                }
                val storedEnd = if (incoming.endTs == null) {
                    val currentOpen = sessionDao.findOpenSession()
                    when {
                        currentOpen == null || currentOpen.startTs == incoming.startTs -> null
                        currentOpen.startTs < incoming.startTs -> {
                            sessionDao.closeOpenSessions(incoming.startTs)
                            null
                        }
                        else -> currentOpen.startTs
                    }
                } else {
                    incoming.endTs
                }
                val existing = sessionDao.findByStartEnd(incoming.startTs, storedEnd)
                val stored: StreamSessionEntity
                if (existing == null) {
                    val entity = StreamSessionEntity(
                        startTs = incoming.startTs,
                        endTs = storedEnd,
                        title = incoming.title,
                        coverPath = coverPath
                    )
                    stored = entity.copy(id = sessionDao.insertSession(entity))
                    sessionAdded++
                } else {
                    val merged = existing.copy(
                        title = existing.title?.takeIf { it.isNotBlank() } ?: incoming.title,
                        coverPath = existing.coverPath?.takeIf { File(it).isFile } ?: coverPath
                    )
                    if (merged != existing) {
                        sessionDao.updateSession(merged)
                        sessionMerged++
                    } else {
                        sessionSkipped++
                    }
                    stored = merged
                }
                restoredSessions[incoming.startTs to incoming.endTs] = stored
            }

            data.moods.forEach { incoming ->
                require(incoming.durationMin >= 0) { "心情时长不能为负数" }
                val existing = moodDao.findByKey(incoming.eventTs, incoming.mood, incoming.title)
                if (existing == null) {
                    moodDao.insert(incoming.copy(id = 0))
                    moodAdded++
                } else {
                    val merged = existing.copy(
                        durationMin = if (existing.durationMin == 0) incoming.durationMin else existing.durationMin,
                        reason = existing.reason?.takeIf { it.isNotBlank() } ?: incoming.reason,
                        note = existing.note?.takeIf { it.isNotBlank() } ?: incoming.note,
                        createdAt = if (existing.createdAt <= 0) incoming.createdAt else existing.createdAt
                    )
                    if (merged != existing) {
                        moodDao.update(merged)
                        moodMerged++
                    } else {
                        moodSkipped++
                    }
                }
            }

            data.titleChanges.forEach { incoming ->
                val session = restoredSessions[incoming.sessionStart to incoming.sessionEnd]
                    ?: sessionDao.findByStartEnd(incoming.sessionStart, incoming.sessionEnd)
                if (session == null) {
                    titleSkipped++
                    return@forEach
                }
                val existing = sessionDao.findTitleChange(session.id, incoming.changedAt)
                if (existing == null) {
                    sessionDao.insertTitleChange(
                        StreamTitleChangeEntity(
                            sessionId = session.id,
                            changedAt = incoming.changedAt,
                            oldTitle = incoming.oldTitle,
                            newTitle = incoming.newTitle
                        )
                    )
                    titleAdded++
                } else {
                    val merged = existing.copy(
                        oldTitle = existing.oldTitle?.takeIf { it.isNotBlank() } ?: incoming.oldTitle,
                        newTitle = existing.newTitle?.takeIf { it.isNotBlank() } ?: incoming.newTitle
                    )
                    if (merged != existing) {
                        sessionDao.updateTitleChange(merged)
                        titleMerged++
                    } else {
                        titleSkipped++
                    }
                }
            }

            data.popularity.forEach { incoming ->
                val session = restoredSessions[incoming.sessionStart to incoming.sessionEnd]
                    ?: sessionDao.findByStartEnd(incoming.sessionStart, incoming.sessionEnd)
                if (session != null && sessionDao.countPopularity(session.id, incoming.ts) == 0) {
                    sessionDao.insertPopularityPoint(
                        PopularityPointEntity(
                            sessionId = session.id,
                            ts = incoming.ts,
                            online = incoming.online
                        )
                    )
                    popularityAdded++
                } else {
                    popularitySkipped++
                }
            }

            data.followers.forEach { incoming ->
                if (sessionDao.countFollowerSnapshot(incoming.ts) == 0) {
                    sessionDao.insertFollowerSnapshot(incoming.copy(id = 0))
                    followerAdded++
                } else {
                    followerSkipped++
                }
            }
        }

        val preferences = PreferenceManager(context)
        val preferencesResult = data.prefsJson?.let(preferences::importSnapshot)
        if (preferencesResult?.imported == true && preferences.isAutoBackupEnabled() &&
            preferences.getBackupTreeUri().isNotBlank()
        ) {
            BackupWorker.schedule(context)
        } else if (preferencesResult?.imported == true) {
            BackupWorker.cancel(context)
        }

        FullBackup.RestoreReport(
            sessions = FullBackup.RestoreCount(sessionAdded, sessionMerged, sessionSkipped),
            moods = FullBackup.RestoreCount(moodAdded, moodMerged, moodSkipped),
            titleChanges = FullBackup.RestoreCount(titleAdded, titleMerged, titleSkipped),
            popularity = FullBackup.RestoreCount(popularityAdded, skipped = popularitySkipped),
            followers = FullBackup.RestoreCount(followerAdded, skipped = followerSkipped),
            covers = FullBackup.RestoreCount(coverAdded, skipped = coverSkipped),
            preferencesRestored = preferencesResult?.imported == true,
            magicPeriodsRestored = preferencesResult?.magicPeriodsImported == true
        )
    }

    private fun isValidImageFile(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
}
