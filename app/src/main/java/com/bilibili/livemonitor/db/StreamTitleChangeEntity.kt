package com.bilibili.livemonitor.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 直播中标题变化记录（Phase 4.1 主题变化提醒配套）。
 */
@Entity(tableName = "stream_title_changes")
data class StreamTitleChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
    @ColumnInfo(name = "old_title") val oldTitle: String? = null,
    @ColumnInfo(name = "new_title") val newTitle: String? = null
)
