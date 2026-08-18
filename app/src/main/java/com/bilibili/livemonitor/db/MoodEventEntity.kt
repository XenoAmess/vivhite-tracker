package com.bilibili.livemonitor.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 心情事件记录（绮迹手账页按日归属）：用户在选定日期上记录的
 * 「开始时间 + 时长 + 心情 + 事件 + 原因 + 备注」。
 * mood 存 MoodCatalog 的 key（不存 emoji，改文案不污染历史数据）。
 * durationMin=0 表示不记时长（界面只展示开始时间）。
 */
@Entity(
    tableName = "mood_events",
    indices = [Index(value = ["event_ts"])]
)
data class MoodEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_ts") val eventTs: Long,
    @ColumnInfo(name = "duration_min", defaultValue = "0") val durationMin: Int = 0,
    val mood: String,
    val title: String,
    val reason: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
