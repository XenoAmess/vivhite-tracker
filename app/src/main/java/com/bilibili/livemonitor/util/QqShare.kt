package com.bilibili.livemonitor.util

import android.content.Intent
import android.os.Bundle
import com.tencent.connect.share.QQShare

/**
 * 直播间分享。
 * 真卡片走 QQ 互联官方 SDK（Tencent.shareToQQ），
 * 系统分享面板兜底。链接按 B 站原生分享规则带 bbid 归因到指定用户。
 */
object QqShare {

    const val ROOM_ID = 11258892L

    // QQ 互联 APPID（open.qq.com 注册应用）
    const val QQ_APP_ID = "1905299138"

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

    // QQ 互联 SDK 分享参数（真卡片：标题+描述+封面+来源）
    fun buildSdkShareParams(coverUrl: String): Bundle {
        return Bundle().apply {
            putString(QQShare.SHARE_TO_QQ_TITLE, SHARE_TITLE)
            putString(QQShare.SHARE_TO_QQ_SUMMARY, SHARE_DESC)
            putString(QQShare.SHARE_TO_QQ_TARGET_URL, buildShareUrl())
            putString(QQShare.SHARE_TO_QQ_IMAGE_URL, coverUrl)
            putString(QQShare.SHARE_TO_QQ_APP_NAME, APP_NAME)
        }
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

// QQ 互联 SDK 分享调用的包装接口，便于测试注入 fake
interface QqSdkSharer {
    fun shareToQQ(activity: android.app.Activity, params: Bundle)
}

class DefaultQqSdkSharer : QqSdkSharer {
    override fun shareToQQ(activity: android.app.Activity, params: Bundle) {
        val tencent = com.tencent.tauth.Tencent.createInstance(QqShare.QQ_APP_ID, activity)
        val qqShare = com.tencent.connect.share.QQShare(activity, tencent.qqToken)
        qqShare.shareToQQ(activity, params, object : com.tencent.tauth.IUiListener {
            override fun onComplete(response: Any?) {
                AppLogger.d("QqSdkSharer", "qq share complete: $response")
            }

            override fun onError(e: com.tencent.tauth.UiError?) {
                AppLogger.e("QqSdkSharer", "qq share error: ${e?.errorCode} ${e?.errorMessage} ${e?.errorDetail}")
            }

            override fun onCancel() {
                AppLogger.d("QqSdkSharer", "qq share cancelled")
            }

            override fun onWarning(code: Int) {
                AppLogger.w("QqSdkSharer", "qq share warning: $code")
            }
        })
    }
}
