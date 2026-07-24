package com.bilibili.livemonitor.api

/**
 * 直播状态查询接口，便于测试注入 fake。
 * 生产实现是 BilibiliApi。
 */
interface LiveStatusChecker {
    suspend fun checkLiveStatus(roomId: Long): BilibiliApi.LiveStatus
}
