package com.bilibili.livemonitor.util

import android.content.Intent

/**
 * 直播间分享（系统分享路径）。
 * mqqapi scheme 已被证实在新版 QQ 上被静默忽略（用户真机日志：
 * intent launched 但 QQ 无任何响应），不可靠，故主路径改用
 * ACTION_SEND——QQ 在系统分享面板中会基于链接自动生成卡片。
 * 链接按 B 站原生分享规则带 bbid 归因到指定用户。
 */
object QqShare {

    const val ROOM_ID = 11258892L

    // 分享归因用户（B 站 bbid 参数）：琉焰卿Official
    private const val SHARER_UID = "8945059"

    private const val SHARE_TITLE = "白绮开播啦！"
    private const val SHARE_DESC = "B站直播间 11258892，快来看！"

    // 实时封面获取失败时的兜底静态图（仓库内白绮头像）
    const val FALLBACK_COVER_URL =
        "https://raw.githubusercontent.com/XenoAmess/vivhite-tracker/master/resources/on.png"

    // B 站原生分享链接格式：bbid 标识分享者，无签名可固定为指定用户
    fun buildShareUrl(ts: Long = System.currentTimeMillis()): String {
        return "https://live.bilibili.com/$ROOM_ID" +
            "?broadcast_type=0&share_source=copy_link&share_medium=android" +
            "&bbid=$SHARER_UID&ts=$ts"
    }

    // 系统分享 intent：标题 + 描述 + 归因链接
    fun buildSystemShareIntent(): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, SHARE_TITLE)
            putExtra(Intent.EXTRA_TEXT, "$SHARE_DESC ${buildShareUrl()}")
        }
    }
}
