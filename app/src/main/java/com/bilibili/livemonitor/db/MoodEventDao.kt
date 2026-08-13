package com.bilibili.livemonitor.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface MoodEventDao {

    @Insert
    suspend fun insert(event: MoodEventEntity): Long

    @Update
    suspend fun update(event: MoodEventEntity)

    @Delete
    suspend fun delete(event: MoodEventEntity)

    /** 某本地日内（[fromInclusive, toExclusive)）的心情事件，按发生时间升序 */
    @Query("SELECT * FROM mood_events WHERE event_ts >= :fromInclusive AND event_ts < :toExclusive ORDER BY event_ts ASC")
    suspend fun eventsBetween(fromInclusive: Long, toExclusive: Long): List<MoodEventEntity>

    /** 全部心情事件（备份导出用），按发生时间升序 */
    @Query("SELECT * FROM mood_events ORDER BY event_ts ASC")
    suspend fun all(): List<MoodEventEntity>

    /** 导入去重：同 时间+心情+标题 视为重复 */
    @Query("SELECT COUNT(*) FROM mood_events WHERE event_ts = :eventTs AND mood = :mood AND title = :title")
    suspend fun countByKey(eventTs: Long, mood: String, title: String): Int

    /** 清空（测试用） */
    @Query("DELETE FROM mood_events")
    suspend fun deleteAll()

    /** 指定时间之前的心情事件数（删除预览用） */
    @Query("SELECT COUNT(*) FROM mood_events WHERE event_ts < :before")
    suspend fun beforeCount(before: Long): Int

    @Query("DELETE FROM mood_events WHERE event_ts < :before")
    suspend fun deleteBefore(before: Long): Int
}
