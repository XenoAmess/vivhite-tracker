package com.bilibili.livemonitor.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface StreamSessionDao {

    @Insert
    suspend fun insertSession(session: StreamSessionEntity): Long

    @Update
    suspend fun updateSession(session: StreamSessionEntity)

    @Delete
    suspend fun deleteSession(session: StreamSessionEntity)

    /** 最近的未闭合场次（进程死亡后重启仍保留在库里） */
    @Query("SELECT * FROM stream_sessions WHERE end_ts IS NULL ORDER BY start_ts DESC LIMIT 1")
    suspend fun findOpenSession(): StreamSessionEntity?

    @Query("SELECT * FROM stream_sessions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): StreamSessionEntity?

    /** 下载封面期间标题/结束状态可能变化，只定向补空封面，禁止旧实体整行覆盖。 */
    @Query("UPDATE stream_sessions SET cover_path = :path WHERE id = :id AND cover_path IS NULL")
    suspend fun setCoverIfMissing(id: Long, path: String): Int

    @Query("UPDATE stream_sessions SET cover_path = :path WHERE id = :id")
    suspend fun setCoverPath(id: Long, path: String): Int

    /** 新场次开始时闭合残留行；异常时钟不得制造 end_ts < start_ts。 */
    @Query("UPDATE stream_sessions SET end_ts = MAX(start_ts, :endTs) WHERE end_ts IS NULL")
    suspend fun closeOpenSessions(endTs: Long)

    @Query("UPDATE stream_sessions SET end_ts = MAX(start_ts, :endTs) WHERE end_ts IS NULL AND id != :keepId")
    suspend fun closeOtherOpenSessions(keepId: Long, endTs: Long)

    @Query(
        "UPDATE stream_sessions SET end_ts = MAX(start_ts, :endTs), " +
            "title = COALESCE(title, :title) WHERE end_ts IS NULL"
    )
    suspend fun closeOpenSessions(endTs: Long, title: String?)

    /** 场次开始的查重、残留闭合和插入必须处于同一事务。 */
    @Transaction
    suspend fun beginSession(startTs: Long, title: String?): Long {
        val open = findOpenSession()
        if (open != null && open.startTs == startTs) {
            closeOtherOpenSessions(open.id, startTs)
            return open.id
        }
        closeOpenSessions(startTs)
        return insertSession(StreamSessionEntity(startTs = startTs, title = title))
    }

    /** 闭合全部残留开放行，并返回用于提醒时长的最近一场开始时间。 */
    @Transaction
    suspend fun endOpenSessions(endTs: Long, title: String?): Long? {
        val open = findOpenSession() ?: return null
        closeOpenSessions(endTs, title)
        return open.startTs
    }

    @Query(
        "UPDATE stream_sessions SET start_ts = :startTs, end_ts = :endTs, title = :title " +
            "WHERE id = :id AND end_ts IS :expectedEndTs"
    )
    suspend fun updateDetailsIfEndUnchanged(
        id: Long,
        expectedEndTs: Long?,
        startTs: Long,
        endTs: Long?,
        title: String?
    ): Int

    /** 清空（测试用） */
    @Query("DELETE FROM stream_sessions")
    suspend fun deleteAll()

    @Query("SELECT * FROM stream_sessions ORDER BY start_ts DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<StreamSessionEntity>

    /** 全量备份专用，不得加 LIMIT。 */
    @Query("SELECT * FROM stream_sessions ORDER BY start_ts ASC")
    suspend fun allSessions(): List<StreamSessionEntity>

    /** 全历史标题搜索；instr 把用户输入按字面量处理，不把 %/_ 当通配符。 */
    @Query("SELECT * FROM stream_sessions WHERE title IS NOT NULL AND instr(lower(title), lower(:query)) > 0 ORDER BY start_ts DESC")
    suspend fun searchSessions(query: String): List<StreamSessionEntity>

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

    @Insert
    suspend fun insertFollowerSnapshot(snapshot: FollowerSnapshotEntity)

    /** 全部粉丝快照，按时间升序（曲线绘制用） */
    @Query("SELECT * FROM follower_snapshots ORDER BY ts ASC")
    suspend fun followerSnapshots(): List<FollowerSnapshotEntity>

    /** 最近一次快照时间（天闸用）；无记录返回 null */
    @Query("SELECT MAX(ts) FROM follower_snapshots")
    suspend fun lastFollowerSnapshotTs(): Long?

    /** 清空粉丝快照（测试用） */
    @Query("DELETE FROM follower_snapshots")
    suspend fun deleteAllFollowerSnapshots()

    // ---------- 批量管理（手账页） ----------

    /** 指定时间之前的已闭合场次数（开放场次始终保留）。 */
    @Query("SELECT COUNT(*) FROM stream_sessions WHERE start_ts < :before AND end_ts IS NOT NULL")
    suspend fun sessionsBeforeCount(before: Long): Int

    /** 级联：主题变化/人气点挂在场次上，先删子表 */
    @Query("DELETE FROM stream_title_changes WHERE session_id IN (SELECT id FROM stream_sessions WHERE start_ts < :before AND end_ts IS NOT NULL)")
    suspend fun deleteTitleChangesBefore(before: Long): Int

    @Query("DELETE FROM popularity_points WHERE session_id IN (SELECT id FROM stream_sessions WHERE start_ts < :before AND end_ts IS NOT NULL)")
    suspend fun deletePopularityBefore(before: Long): Int

    @Query("DELETE FROM stream_sessions WHERE start_ts < :before AND end_ts IS NOT NULL")
    suspend fun deleteSessionsBefore(before: Long): Int

    @Query("DELETE FROM stream_sessions WHERE end_ts IS NOT NULL")
    suspend fun deleteAllClosedSessions(): Int

    @Query("DELETE FROM stream_title_changes")
    suspend fun deleteAllTitleChanges()

    /** 全部场次标题（词云数据源；空标题剔除） */
    @Query("SELECT title FROM stream_sessions WHERE title IS NOT NULL AND title != ''")
    suspend fun allSessionTitles(): List<String>

    /** 全部主题变化的新标题（词云数据源） */
    @Query("SELECT new_title FROM stream_title_changes WHERE new_title IS NOT NULL AND new_title != ''")
    suspend fun allChangeTitles(): List<String>

    /** 某时间区间的场次（日历按月加载用），按开始时间升序 */
    @Query("SELECT * FROM stream_sessions WHERE start_ts >= :from AND start_ts < :to ORDER BY start_ts ASC")
    suspend fun sessionsBetween(from: Long, to: Long): List<StreamSessionEntity>

    // ---------- 全量备份（FullBackup） ----------

    @Query("SELECT * FROM stream_title_changes ORDER BY changed_at ASC")
    suspend fun allTitleChanges(): List<StreamTitleChangeEntity>

    @Query("SELECT * FROM popularity_points ORDER BY ts ASC")
    suspend fun allPopularityPoints(): List<PopularityPointEntity>

    /** 按起止时间找场次（导入侧 id 映射用；end_ts IS 同时覆盖 NULL 与等值） */
    @Query("SELECT * FROM stream_sessions WHERE start_ts = :startTs AND end_ts IS :endTs LIMIT 1")
    suspend fun findByStartEnd(startTs: Long, endTs: Long?): StreamSessionEntity?

    /** 某时间区间的人气采样点（月度曲线用），按时间升序 */
    @Query("SELECT * FROM popularity_points WHERE ts >= :from AND ts < :to ORDER BY ts ASC")
    suspend fun popularityBetween(from: Long, to: Long): List<PopularityPointEntity>

    /** 按开播月份取场次的完整人气曲线，包含跨到次月后的采样点。 */
    @Query(
        "SELECT p.* FROM popularity_points p " +
            "INNER JOIN stream_sessions s ON s.id = p.session_id " +
            "WHERE s.start_ts >= :from AND s.start_ts < :to ORDER BY p.ts ASC"
    )
    suspend fun popularityForSessionsStartingBetween(from: Long, to: Long): List<PopularityPointEntity>

    /** 某时间区间的主题变化新标题（月度词云用） */
    @Query("SELECT new_title FROM stream_title_changes WHERE changed_at >= :from AND changed_at < :to AND new_title IS NOT NULL AND new_title != ''")
    suspend fun changeTitlesBetween(from: Long, to: Long): List<String>

    /** 导入去重：同 场次+变更时间 视为重复 */
    @Query("SELECT COUNT(*) FROM stream_title_changes WHERE session_id = :sessionId AND changed_at = :changedAt")
    suspend fun countTitleChange(sessionId: Long, changedAt: Long): Int

    @Query("SELECT * FROM stream_title_changes WHERE session_id = :sessionId AND changed_at = :changedAt LIMIT 1")
    suspend fun findTitleChange(sessionId: Long, changedAt: Long): StreamTitleChangeEntity?

    @Update
    suspend fun updateTitleChange(change: StreamTitleChangeEntity)

    /** 导入去重：同 场次+采样时间 视为重复 */
    @Query("SELECT COUNT(*) FROM popularity_points WHERE session_id = :sessionId AND ts = :ts")
    suspend fun countPopularity(sessionId: Long, ts: Long): Int

    /** 导入去重：同时间粉丝快照视为重复 */
    @Query("SELECT COUNT(*) FROM follower_snapshots WHERE ts = :ts")
    suspend fun countFollowerSnapshot(ts: Long): Int
}
