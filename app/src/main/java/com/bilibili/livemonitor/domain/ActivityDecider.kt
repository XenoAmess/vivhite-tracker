package com.bilibili.livemonitor.domain

/**
 * B 站全活动监控的活动类型。
 */
sealed class ActivityType {
    object Live : ActivityType()
    data class Video(val aid: Long, val title: String) : ActivityType()
    data class Pinned(val aid: Long, val title: String) : ActivityType()
    data class Dynamic(val id: String, val displayText: String) : ActivityType()
}

/**
 * 活动提醒决策（纯函数，无 Android 依赖）。
 *
 * 核心原则：**首次不提醒**——App 新装/升级后第一次检测只记录当前最新 id，
 * 不触发提醒。否则用户装完瞬间收到"新视频"通知（实际是历史视频）。
 *
 * lastAid/lastPinnedAid 用 Long? 而非 Long(-1)，让"未初始化"和"有效值=0"
 * 语义清晰（avid 不会是 0，但用 null 更安全）。
 * lastDynamicId 用 String? 而非空串，同理。
 */
object ActivityDecider {

    /**
     * 新视频提醒：lastAid=null（首次）不提；aid 变化才提。
     */
    fun shouldAlertVideo(newAid: Long, lastAid: Long?): Boolean {
        return lastAid != null && newAid != lastAid
    }

    /**
     * 置顶变化提醒：lastAid=null 不提；新置顶=null（UP 取消置顶）也算变化。
     */
    fun shouldAlertPinned(newAid: Long?, lastAid: Long?): Boolean {
        return lastAid != null && newAid != lastAid
    }

    /**
     * 新动态提醒：lastId=null（首次）不提；id 变化才提。
     */
    fun shouldAlertDynamic(newId: String, lastId: String?): Boolean {
        return lastId != null && newId != lastId
    }

    /**
     * 把 prefs 里的 Long（-1 = 未初始化）转成 nullable。
     */
    fun longToNullable(value: Long): Long? = if (value < 0) null else value

    /**
     * 把 prefs 里的 String（空串 = 未初始化）转成 nullable。
     */
    fun stringToNullable(value: String): String? = if (value.isBlank()) null else value
}
