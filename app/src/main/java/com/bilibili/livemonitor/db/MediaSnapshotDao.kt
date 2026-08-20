package com.bilibili.livemonitor.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MediaSnapshotDao {

    @Insert
    suspend fun insertSnapshot(snapshot: MediaSnapshotEntity): Long

    @Query("SELECT * FROM media_snapshots ORDER BY observed_at ASC, id ASC")
    suspend fun allSnapshots(): List<MediaSnapshotEntity>

    /** Change detection follows insertion order; wall clock can move backwards or restored data may be skewed. */
    @Query("SELECT * FROM media_snapshots WHERE kind = :kind ORDER BY id DESC LIMIT 1")
    suspend fun latestSnapshot(kind: String): MediaSnapshotEntity?

    @Query(
        "SELECT * FROM media_snapshots WHERE kind = :kind AND session_start_ts IS :sessionStartTs " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun latestForSession(kind: String, sessionStartTs: Long?): MediaSnapshotEntity?

    @Query(
        "SELECT * FROM media_snapshots WHERE kind = :kind AND observed_at <= :timestamp " +
            "ORDER BY observed_at DESC, id DESC LIMIT 1"
    )
    suspend fun snapshotAtOrBefore(kind: String, timestamp: Long): MediaSnapshotEntity?

    @Query(
        "SELECT * FROM media_snapshots WHERE kind = :kind AND observed_at <= :timestamp " +
            "ORDER BY observed_at DESC, id DESC"
    )
    suspend fun snapshotsAtOrBefore(kind: String, timestamp: Long): List<MediaSnapshotEntity>

    @Query("SELECT * FROM media_snapshots WHERE kind = :kind ORDER BY observed_at ASC, id ASC")
    suspend fun snapshotsOldestFirst(kind: String): List<MediaSnapshotEntity>

    @Query(
        "SELECT * FROM media_snapshots WHERE kind = :kind " +
            "AND session_start_ts >= :from AND session_start_ts < :to " +
            "ORDER BY observed_at ASC, id ASC"
    )
    suspend fun snapshotsForSessionsStartingBetween(
        kind: String,
        from: Long,
        to: Long
    ): List<MediaSnapshotEntity>

    @Query("SELECT * FROM media_snapshots WHERE kind = :kind ORDER BY observed_at ASC, id ASC LIMIT 1")
    suspend fun firstSnapshot(kind: String): MediaSnapshotEntity?

    @Query(
        "SELECT COUNT(*) FROM media_snapshots WHERE kind = :kind AND observed_at = :observedAt " +
            "AND content_key = :contentKey AND session_start_ts IS :sessionStartTs"
    )
    suspend fun countSnapshot(
        kind: String,
        observedAt: Long,
        contentKey: String,
        sessionStartTs: Long?
    ): Int

    @Query(
        "SELECT COUNT(*) FROM media_snapshots WHERE kind = :kind AND content_key = :contentKey " +
            "AND session_start_ts IS :sessionStartTs"
    )
    suspend fun countContentForSession(kind: String, contentKey: String, sessionStartTs: Long?): Int

    @Query("SELECT * FROM media_snapshots WHERE kind = :kind AND content_key = :contentKey ORDER BY observed_at DESC LIMIT 1")
    suspend fun findByContentKey(kind: String, contentKey: String): MediaSnapshotEntity?

    @Query("UPDATE media_snapshots SET file_name = :fileName WHERE kind = :kind AND content_key = :contentKey")
    suspend fun updateFileName(kind: String, contentKey: String, fileName: String): Int

    @Query("DELETE FROM media_snapshots")
    suspend fun deleteAll()
}
