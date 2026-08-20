package com.bilibili.livemonitor.util

import android.content.Context
import androidx.room.withTransaction
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.domain.FullBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** Builds one consistent database snapshot and streams it, plus prefs and cover files, into a ZIP. */
object FullBackupBuilder {

    suspend fun build(context: Context): ByteArray {
        val out = ByteArrayOutputStream()
        write(context, out)
        return out.toByteArray()
    }

    suspend fun write(context: Context, output: OutputStream) = withContext(Dispatchers.IO) {
        MediaHistoryImporter.ensureImported(context)
        val db = AppDatabase.get(context)
        val snapshot = db.withTransaction {
            val sessions = db.streamSessionDao().allSessions()
            val byId = sessions.associateBy { it.id }
            FullBackup.Data(
                sessions = sessions,
                moods = db.moodEventDao().all(),
                titleChanges = db.streamSessionDao().allTitleChanges().mapNotNull { tc ->
                    val session = byId[tc.sessionId] ?: return@mapNotNull null
                    FullBackup.TitleChangeRow(
                        session.startTs, session.endTs, tc.changedAt, tc.oldTitle, tc.newTitle
                    )
                },
                popularity = db.streamSessionDao().allPopularityPoints().mapNotNull { point ->
                    val session = byId[point.sessionId] ?: return@mapNotNull null
                    FullBackup.PopularityRow(
                        session.startTs, session.endTs, point.ts, point.online
                    )
                },
                followers = db.streamSessionDao().followerSnapshots(),
                prefsJson = null,
                mediaSnapshots = db.mediaSnapshotDao().allSnapshots()
            )
        }

        val coverFiles = File(context.filesDir, "covers").listFiles().orEmpty()
            .filter(::isValidImage)
            .associateBy { it.name }
        val avatarFiles = File(context.filesDir, "avatars").listFiles().orEmpty()
            .filter(::isValidImage)
            .associateBy { it.name }
        val mediaStore = MediaStore()
        snapshot.mediaSnapshots.forEach { row ->
            val file = if (row.kind == com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_AVATAR) {
                avatarFiles[row.fileName]
            } else {
                coverFiles[row.fileName]
            }
            if (file == null || mediaStore.sha1Hex(file) != row.contentKey) {
                throw java.io.IOException("媒体索引与原图不一致：${row.fileName}")
            }
        }
        FullBackup.pack(
            snapshot.copy(
                prefsJson = PreferenceManager(context).exportSnapshot(),
                coverFiles = coverFiles,
                avatarFiles = avatarFiles,
                mediaSnapshots = snapshot.mediaSnapshots
            ),
            output
        )
    }

    private fun isValidImage(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
}
