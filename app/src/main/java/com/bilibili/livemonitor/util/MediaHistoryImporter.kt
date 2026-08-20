package com.bilibili.livemonitor.util

import android.content.Context
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MediaSnapshotEntity
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One-time best-effort indexing of images written by versions before media_snapshots existed. */
object MediaHistoryImporter {

    suspend fun ensureImported(context: Context) = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val prefs = PreferenceManager(context)
            if (prefs.isLegacyMediaImported()) return@withLock
            val database = AppDatabase.get(context)
            val mediaDao = database.mediaSnapshotDao()
            val mediaStore = MediaStore()

            database.withTransaction {
                val legacyAvatar = File(context.filesDir, "anchor_avatar.jpg")
                if (mediaDao.latestSnapshot(MediaSnapshotEntity.KIND_AVATAR) == null &&
                    mediaStore.isValidImage(legacyAvatar)
                ) {
                    val key = mediaStore.sha1Hex(legacyAvatar)
                    val destination = mediaStore.fileFor(context, MediaSnapshotEntity.KIND_AVATAR, key)
                    check(copyAtomically(legacyAvatar, destination, key)) {
                        "无法迁移旧头像"
                    }
                    mediaDao.insertSnapshot(
                        MediaSnapshotEntity(
                            kind = MediaSnapshotEntity.KIND_AVATAR,
                            observedAt = legacyAvatar.lastModified().takeIf { it > 0 }
                                ?: System.currentTimeMillis(),
                            contentKey = key,
                            fileName = destination.name
                        )
                    )
                }

                database.streamSessionDao().allSessions().forEach { session ->
                    val file = session.coverPath?.let(::File)?.takeIf(mediaStore::isValidImage)
                        ?: return@forEach
                    val key = mediaStore.sha1Hex(file)
                    if (mediaDao.countContentForSession(
                            MediaSnapshotEntity.KIND_ROOM_COVER,
                            key,
                            session.startTs
                        ) == 0
                    ) {
                        mediaDao.insertSnapshot(
                            MediaSnapshotEntity(
                                kind = MediaSnapshotEntity.KIND_ROOM_COVER,
                                observedAt = session.startTs,
                                contentKey = key,
                                fileName = file.name,
                                sessionStartTs = session.startTs,
                                title = session.title
                            )
                        )
                    }
                }
            }
            prefs.setLegacyMediaImported(true)
        }
    }

    private val importMutex = Mutex()

    private fun copyAtomically(source: File, destination: File, expectedHash: String): Boolean {
        val store = MediaStore()
        if (store.isValidImage(destination) && store.sha1Hex(destination) == expectedHash) return true
        destination.delete()
        destination.parentFile?.mkdirs()
        val temp = File.createTempFile(".${destination.name}.", ".part", destination.parentFile)
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    (output as? java.io.FileOutputStream)?.fd?.sync()
                }
            }
            if (!store.isValidImage(temp) || store.sha1Hex(temp) != expectedHash ||
                !AppUpdater.publishAtomically(temp, destination)
            ) {
                return false
            }
            return store.isValidImage(destination) && store.sha1Hex(destination) == expectedHash
        } finally {
            temp.delete()
        }
    }
}
