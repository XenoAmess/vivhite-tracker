package com.bilibili.livemonitor.util

import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * QQ 分享卡片（mqqapi scheme 免 SDK 方案）。
 * 分享链接按 B 站原生分享规则带 bbid 归因到指定用户。
 */
object QqShare {

    const val ROOM_ID = 11258892L

    // 分享归因用户（B 站 bbid 参数）：琉焰卿Official
    private const val SHARER_UID = "8945059"

    private const val SHARE_TITLE = "白绮开播啦！"
    private const val SHARE_DESC = "B站直播间 11258892，快来看！"
    private const val APP_NAME = "牢白播了吗"

    // 实时封面获取失败时的兜底静态图（仓库内白绮头像）
    const val FALLBACK_COVER_URL =
        "https://raw.githubusercontent.com/XenoAmess/vivhite-tracker/master/resources/on.png"

    // B 站原生分享链接格式：bbid 标识分享者，无签名可固定为指定用户
    fun buildShareUrl(ts: Long = System.currentTimeMillis()): String {
        return "https://live.bilibili.com/$ROOM_ID" +
            "?broadcast_type=0&share_source=copy_link&share_medium=android" +
            "&bbid=$SHARER_UID&ts=$ts"
    }

    // mqqapi 富卡片分享 intent：标题+描述+封面+来源(app_name)
    fun buildQqShareIntent(coverUrl: String, qqPackage: String?): Intent {
        val url = "mqqapi://share/to_friend?src_type=app&version=1&file_type=news" +
            "&image_url=${enc(coverUrl)}" +
            "&title=${enc(SHARE_TITLE)}" +
            "&description=${enc(SHARE_DESC)}" +
            "&url=${enc(buildShareUrl())}" +
            "&app_name=${enc(APP_NAME)}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (!qqPackage.isNullOrEmpty()) setPackage(qqPackage)
        }
    }

    // 未装 QQ 时的系统分享兜底（纯文本）
    fun buildSystemShareIntent(): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$SHARE_TITLE $SHARE_DESC ${buildShareUrl()}")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
