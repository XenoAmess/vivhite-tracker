package com.bilibili.livemonitor.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 心情事件记录（场次记录页按日归属）：用户在选定日期上记录的
 * 「时间 + 心情 + 事件 + 原因 + 备注」。
 * mood 存 MoodCatalog 的 key（不存 emoji，改文案不污染历史数据）。
 */
@Entity(tableName = "mood_events")
data class MoodEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_ts") val eventTs: Long,
    val mood: String,
    val title: String,
    val reason: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
