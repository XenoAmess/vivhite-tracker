package com.bilibili.livemonitor.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 直播人气采样点（60s 轮询顺手存，data.online）。
 * 挂在场次上（session_id），场次详情弹窗画曲线。
 */
@Entity(
    tableName = "popularity_points",
    foreignKeys = [
        ForeignKey(
            entity = StreamSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id", "ts"])]
)
data class PopularityPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val ts: Long,
    val online: Int
)
