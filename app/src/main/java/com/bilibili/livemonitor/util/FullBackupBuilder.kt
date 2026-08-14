package com.bilibili.livemonitor.util

import android.content.Context
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.domain.FullBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全量备份组装（自动备份 Worker 与手账页手动导出共用）：
 * DB 全表 + prefs 快照 + covers 原图 → ZIP 字节。
 */
object FullBackupBuilder {

    suspend fun build(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val sessions = db.streamSessionDao().recentSessions(500)
        val byId = sessions.associateBy { it.id }

        val titleChanges = db.streamSessionDao().allTitleChanges().mapNotNull { tc ->
            val s = byId[tc.sessionId] ?: return@mapNotNull null
            FullBackup.TitleChangeRow(
                sessionStart = s.startTs, sessionEnd = s.endTs,
                changedAt = tc.changedAt, oldTitle = tc.oldTitle, newTitle = tc.newTitle
            )
        }
        val popularity = db.streamSessionDao().allPopularityPoints().mapNotNull { p ->
            val s = byId[p.sessionId] ?: return@mapNotNull null
            FullBackup.PopularityRow(
                sessionStart = s.startTs, sessionEnd = s.endTs,
                ts = p.ts, online = p.online
            )
        }

        val covers = mutableMapOf<String, ByteArray>()
        File(context.filesDir, "covers").listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 0) {
                runCatching { covers[f.name] = f.readBytes() }
            }
        }

        FullBackup.pack(
            FullBackup.Data(
                sessions = sessions,
                moods = db.moodEventDao().all(),
                titleChanges = titleChanges,
                popularity = popularity,
                followers = db.streamSessionDao().followerSnapshots(),
                prefsJson = PreferenceManager(context).exportSnapshot(),
                covers = covers
            )
        )
    }
}
