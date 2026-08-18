package com.bilibili.livemonitor.repository

import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.FollowerSnapshotEntity
import com.bilibili.livemonitor.db.PopularityPointEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.SessionSearch

class StatsRepository(private val database: AppDatabase) {

    data class TrendData(
        val sessions: List<StreamSessionEntity>,
        val followers: List<FollowerSnapshotEntity>,
        val popularity: List<PopularityPointEntity>,
        val titles: List<String>
    )

    suspend fun allSessions(): List<StreamSessionEntity> =
        database.streamSessionDao().allSessions()

    suspend fun sessionsBetween(from: Long, to: Long): List<StreamSessionEntity> =
        database.streamSessionDao().sessionsBetween(from, to)

    suspend fun search(query: String): List<SessionSearch.Hit> {
        // SQLite lower() only folds ASCII on Android. JVM filtering keeps Unicode case-insensitive
        // search correct across titles, free text, and localized mood display names.
        return SessionSearch.search(
            database.streamSessionDao().allSessions(),
            database.moodEventDao().all(),
            query
        )
    }

    suspend fun trendData(since: Long, popularityFrom: Long, popularityTo: Long): TrendData {
        val dao = database.streamSessionDao()
        return TrendData(
            sessions = dao.closedSessionsSince(since),
            followers = dao.followerSnapshots(),
            popularity = dao.popularityBetween(popularityFrom, popularityTo),
            titles = dao.allSessionTitles() + dao.allChangeTitles()
        )
    }
}
