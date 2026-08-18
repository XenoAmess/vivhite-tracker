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
                prefsJson = null
            )
        }

        val coverFiles = File(context.filesDir, "covers").listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 }
            .associateBy { it.name }
        FullBackup.pack(
            snapshot.copy(
                prefsJson = PreferenceManager(context).exportSnapshot(),
                coverFiles = coverFiles
            ),
            output
        )
    }
}
