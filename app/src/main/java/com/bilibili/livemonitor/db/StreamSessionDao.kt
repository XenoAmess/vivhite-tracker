package com.bilibili.livemonitor.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface StreamSessionDao {

    @Insert
    suspend fun insertSession(session: StreamSessionEntity): Long

    @Update
    suspend fun updateSession(session: StreamSessionEntity)

    /** 最近的未闭合场次（进程死亡后重启仍保留在库里） */
    @Query("SELECT * FROM stream_sessions WHERE end_ts IS NULL ORDER BY start_ts DESC LIMIT 1")
    suspend fun findOpenSession(): StreamSessionEntity?

    /** 新场次开始时，把任何残留未闭合场次按给定 endTs 闭合（防进程死亡留下的脏行） */
    @Query("UPDATE stream_sessions SET end_ts = :endTs WHERE end_ts IS NULL")
    suspend fun closeOpenSessions(endTs: Long)

    @Query("SELECT * FROM stream_sessions ORDER BY start_ts DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<StreamSessionEntity>

    /** since 之后的已闭合场次（统计用） */
    @Query("SELECT * FROM stream_sessions WHERE end_ts IS NOT NULL AND start_ts >= :since ORDER BY start_ts DESC")
    suspend fun closedSessionsSince(since: Long): List<StreamSessionEntity>

    @Insert
    suspend fun insertTitleChange(change: StreamTitleChangeEntity)

    @Query("SELECT * FROM stream_title_changes WHERE session_id = :sessionId ORDER BY changed_at ASC")
    suspend fun titleChanges(sessionId: Long): List<StreamTitleChangeEntity>
}
