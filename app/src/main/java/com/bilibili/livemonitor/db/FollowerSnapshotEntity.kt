package com.bilibili.livemonitor.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 粉丝数每日快照（Master/info 的 data.follower_num，检测循环里按天闸采样）。
 */
@Entity(tableName = "follower_snapshots")
data class FollowerSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    @ColumnInfo(name = "follower_num") val followerNum: Long
)
