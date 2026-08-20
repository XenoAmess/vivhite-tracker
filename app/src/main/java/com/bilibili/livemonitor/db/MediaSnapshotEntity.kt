package com.bilibili.livemonitor.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 主播头像与直播封面的发现事件。图片按 [contentKey] 去重落盘，事件行保留
 * A -> B -> A 回退以及同一封面跨场次复用的时间线。
 */
@Entity(
    tableName = "media_snapshots",
    indices = [
        Index(value = ["kind", "observed_at"]),
        Index(value = ["kind", "content_key"]),
        Index(value = ["session_start_ts"])
    ]
)
data class MediaSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "session_start_ts") val sessionStartTs: Long? = null,
    val title: String? = null
) {
    companion object {
        const val KIND_AVATAR = "avatar"
        const val KIND_ROOM_COVER = "room_cover"
    }
}
