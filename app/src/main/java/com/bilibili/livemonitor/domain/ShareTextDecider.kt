package com.bilibili.livemonitor.domain

/**
 * 分享文案决策（纯函数，无 Android 依赖，可纯 JVM 单测）。
 *
 * 用户场景（2026-08 需求）：未开播时分享出去的内容必须体现
 * "还没开播，期待开播"的意向，而不是像旧版那样误报"开播了"。
 * QQ 卡片 / 系统分享 / 图文分享 / 长宣传图共用同一套文案。
 */
object ShareTextDecider {

    /** 分享标题（QQ 卡片 TITLE / 系统分享 SUBJECT / 长图主标题） */
    fun title(isLive: Boolean, liveTitle: String?): String =
        if (isLive) {
            if (!liveTitle.isNullOrBlank()) "「$liveTitle」" else "白绮开播啦！"
        } else {
            "白绮还没开播"
        }

    /** 卡片摘要（QQ 卡片 SUMMARY，一行短描述） */
    fun summary(isLive: Boolean, roomId: Long): String =
        if (isLive) "白绮正在直播 · $roomId" else "白绮还没开播 · $roomId"

    /** 正文（系统分享 / 图文分享的 EXTRA_TEXT，链接由调用方拼接） */
    fun body(isLive: Boolean, roomId: Long, liveTitle: String?): String =
        if (isLive) {
            if (!liveTitle.isNullOrBlank()) "白绮正在直播 · $roomId · 「$liveTitle」"
            else "B站直播间 $roomId，快来看！"
        } else {
            "白绮还没开播，先来直播间蹲一个开播！"
        }
}
