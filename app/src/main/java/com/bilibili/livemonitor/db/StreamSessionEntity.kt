package com.bilibili.livemonitor.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 直播场次记录：开播时插入（endTs=null），下播时闭合。
 * 进程死亡时未闭合的行保留在库里，重启后检测到 NotLive 再补闭合。
 */
@Entity(tableName = "stream_sessions")
data class StreamSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long? = null,
    val title: String? = null
)
