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

    /** 清空（测试用） */
    @Query("DELETE FROM mood_events")
    suspend fun deleteAll()
}
