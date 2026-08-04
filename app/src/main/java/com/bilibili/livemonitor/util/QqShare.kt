package com.bilibili.livemonitor.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tencent.connect.share.QQShare

/**
 * 直播间分享。
 * 真卡片走 QQ 互联官方 SDK（Tencent.shareToQQ），
 * 系统分享面板兜底。链接按 B 站原生分享规则带 bbid 归因到指定用户。
 *
 * # QQ 互联授权流程
 *
 * 腾讯开放平台对第三方应用采用"应用审核 + 用户授权"双层校验：
 * 1. 应用通过审核（open.qq.com 后台状态 OK）
 * 2. **用户必须先在 QQ 端授权**该 App 使用 QQ 互联能力（首次 shareToQQ 弹 QQ 授权页）
 *
 * 2026-07 用户反馈：审核通过后首次分享仍返回 errorCode=-6（用户未授权）。
 * 根因：当前实现未先调 `Tencent.isSessionValid()` 检查，
 * 直接 `shareToQQ` 会立即失败。修复：在 shareToQQ 前先校验 session，
 * 未授权时主动调 `Tencent.login()` 触发 QQ 授权页。
 *
 * # 异步错误兜底
 *
 * 旧版 catch 只兜底同步异常，异步 onError 回调里没处理 → 分享"静默失败"。
 * 修复：onError 回调里检测 -6（未授权）或其他错误码，自动 fallback 到系统分享 + Toast。
 */
object QqShare {

    const val ROOM_ID = 11258892L

    // QQ 互联 APPID（open.qq.com 注册应用）
    const val QQ_APP_ID = "1905299138"

    // 分享归因用户（B 站 bbid 参数）：琉焰卿Official
    private const val SHARER_UID = "8945059"
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

    /**
     * QQ 互联 SDK 分享参数（真卡片：标题+描述+封面+来源）。
     * @param coverUrl 缩略图 URL（方形最佳：开播=直播封面，未开播=白绮头像）
     * @param liveTitle 直播标题（如 "失眠 无言"）。null → 兜底硬编码
     * @param isLive 实时开播状态：false 时文案体现"还没开播，期待开播"
     */
    // 注：曾实验 TYPE_AUDIO 大方图卡，QQ 服务端校验强制要求 music_url
    // （错误码 901114，真机实测）——无音频内容不可用，网页卡是唯一可行模板
    fun buildSdkShareParams(coverUrl: String, liveTitle: String? = null, isLive: Boolean = true): Bundle {
        val title = com.bilibili.livemonitor.domain.ShareTextDecider.qqCardTitle(isLive, liveTitle)
        val summary = com.bilibili.livemonitor.domain.ShareTextDecider.summary(isLive, ROOM_ID)
        return Bundle().apply {
            putInt(QQShare.SHARE_TO_QQ_KEY_TYPE, QQShare.SHARE_TO_QQ_TYPE_DEFAULT)
            putString(QQShare.SHARE_TO_QQ_TITLE, title)
            putString(QQShare.SHARE_TO_QQ_SUMMARY, summary)
            putString(QQShare.SHARE_TO_QQ_TARGET_URL, buildShareUrl())
            putString(QQShare.SHARE_TO_QQ_IMAGE_URL, coverUrl)
            putString(QQShare.SHARE_TO_QQ_APP_NAME, APP_NAME)
            putString(QQShare.SHARE_TO_QQ_SITE, "哔哩哔哩直播")
        }
    }

    /**
     * 系统分享 intent：标题 + 描述 + 归因链接。
     * @param liveTitle 直播标题。null → 兜底硬编码
     * @param isLive 实时开播状态：false 时文案体现"还没开播，期待开播"
     */
    fun buildSystemShareIntent(liveTitle: String? = null, isLive: Boolean = true): Intent {
        val decider = com.bilibili.livemonitor.domain.ShareTextDecider
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, decider.title(isLive, liveTitle))
            putExtra(Intent.EXTRA_TEXT, "${decider.body(isLive, ROOM_ID, liveTitle)} ${buildShareUrl()}")
        }
    }

    /**
     * QQ空间图文说说参数（TYPE_IMAGE_TEXT：标题+文案+本地封面+链接）。
     * 系统 ACTION_SEND 的 EXTRA_TEXT 必被 QQ/微信丢弃——说说是图文进 QQ 系的官方通道。
     * @param localImagePath 封面本地文件路径（QzoneShare 只收本地路径，调用方先下载落盘）
     * @param liveTitle 直播标题。null → 兜底硬编码
     * @param isLive 实时开播状态：false 时文案体现"还没开播，期待开播"
     */
    fun buildQzoneShareParams(localImagePath: String?, liveTitle: String? = null, isLive: Boolean = true): Bundle {
        val decider = com.bilibili.livemonitor.domain.ShareTextDecider
        return Bundle().apply {
            putInt(
                com.tencent.connect.share.QzoneShare.SHARE_TO_QZONE_KEY_TYPE,
                com.tencent.connect.share.QzoneShare.SHARE_TO_QZONE_TYPE_IMAGE_TEXT
            )
            putString(com.tencent.connect.share.QzoneShare.SHARE_TO_QQ_TITLE, decider.title(isLive, liveTitle))
            putString(com.tencent.connect.share.QzoneShare.SHARE_TO_QQ_SUMMARY, decider.body(isLive, ROOM_ID, liveTitle))
            putString(com.tencent.connect.share.QzoneShare.SHARE_TO_QQ_TARGET_URL, buildShareUrl())
            putString(com.tencent.connect.share.QzoneShare.SHARE_TO_QQ_SITE, "哔哩哔哩直播")
            if (!localImagePath.isNullOrBlank()) {
                putString(com.tencent.connect.share.QzoneShare.SHARE_TO_QQ_IMAGE_LOCAL_URL, localImagePath)
            }
        }
    }

    /**
     * 把 Tencent 实例化 + isSessionValid 检查提到这里，避免每次调用都重复创建实例。
     * 旧版 `DefaultQqSdkSharer.shareToQQ` 每次都 `Tencent.createInstance`，
     * 反复 create 会触发 SDK 内部 mTencent 全局状态紊乱。
     */
    fun obtainTencent(context: Context): com.tencent.tauth.Tencent =
        com.tencent.tauth.Tencent.createInstance(QQ_APP_ID, context.applicationContext)
}
