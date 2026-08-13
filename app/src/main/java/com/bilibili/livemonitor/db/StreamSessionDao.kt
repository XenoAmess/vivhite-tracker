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

    /** 清空（测试用） */
    @Query("DELETE FROM stream_sessions")
    suspend fun deleteAll()

    @Query("SELECT * FROM stream_sessions ORDER BY start_ts DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<StreamSessionEntity>

    /** since 之后的已闭合场次（统计用） */
    @Query("SELECT * FROM stream_sessions WHERE end_ts IS NOT NULL AND start_ts >= :since ORDER BY start_ts DESC")
    suspend fun closedSessionsSince(since: Long): List<StreamSessionEntity>

    /** 导入去重：同 开始+结束 视为重复（SQLite IS 同时覆盖 NULL 与等值比较） */
    @Query("SELECT COUNT(*) FROM stream_sessions WHERE start_ts = :startTs AND end_ts IS :endTs")
    suspend fun countByStartEnd(startTs: Long, endTs: Long?): Int

    @Insert
    suspend fun insertTitleChange(change: StreamTitleChangeEntity)

    @Query("SELECT * FROM stream_title_changes WHERE session_id = :sessionId ORDER BY changed_at ASC")
    suspend fun titleChanges(sessionId: Long): List<StreamTitleChangeEntity>

    @Insert
    suspend fun insertPopularityPoint(point: PopularityPointEntity)

    /** 某场次的人气采样点，按时间升序（曲线绘制用） */
    @Query("SELECT * FROM popularity_points WHERE session_id = :sessionId ORDER BY ts ASC")
    suspend fun popularityPoints(sessionId: Long): List<PopularityPointEntity>

    /** 清空人气点（测试用） */
    @Query("DELETE FROM popularity_points")
    suspend fun deleteAllPopularityPoints()
}
