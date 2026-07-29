package com.bilibili.livemonitor.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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

    /**
     * 把 Tencent 实例化 + isSessionValid 检查提到这里，避免每次调用都重复创建实例。
     * 旧版 `DefaultQqSdkSharer.shareToQQ` 每次都 `Tencent.createInstance`，
     * 反复 create 会触发 SDK 内部 mTencent 全局状态紊乱。
     */
    fun obtainTencent(context: Context): com.tencent.tauth.Tencent =
        com.tencent.tauth.Tencent.createInstance(QQ_APP_ID, context.applicationContext)
}

/**
 * 错误码 -6 = 用户未授权（QQ 互联 SDK 标准定义）
 * 其他负数 = 网络/参数错误；正数 = 业务级错误
 */
internal const val QQ_ERR_USER_NOT_AUTHORIZED = -6

/**
 * QQ 互联 SDK 分享调用的包装接口，便于测试注入 fake。
 * 流程：
 * 1. 检查 sessionValid（未授权 → 先 login）
 * 2. login 成功后回调里调 shareToQQ
 * 3. shareToQQ 失败（-6 或其他）→ fallback + Toast
 */
interface QqSdkSharer {
    /**
     * 分享入口。已登录直接 share；未登录先弹 QQ 授权页，授权成功后自动 share。
     * @param onFallback 当 SDK 不可用或同步异常时调用，触发系统分享面板
     */
    fun loginAndShare(
        activity: android.app.Activity,
        params: Bundle,
        onFallback: () -> Unit
    )
}

class DefaultQqSdkSharer : QqSdkSharer {
    override fun loginAndShare(
        activity: android.app.Activity,
        params: Bundle,
        onFallback: () -> Unit
    ) {
        val tencent = QqShare.obtainTencent(activity)
        if (tencent.isSessionValid) {
            doShare(activity, params, onFallback)
        } else {
            // 未授权：弹 QQ 登录授权页，授权回调里再 share
            AppLogger.d(TAG, "qq session invalid, requesting login")
            tencent.login(activity, "all", object : com.tencent.tauth.IUiListener {
                override fun onComplete(response: Any?) {
                    AppLogger.d(TAG, "qq login complete: $response")
                    // 登录成功后再 share
                    doShare(activity, params, onFallback)
                }

                override fun onError(e: com.tencent.tauth.UiError?) {
                    AppLogger.e(TAG, "qq login error: ${e?.errorCode} ${e?.errorMessage}")
                    Toast.makeText(activity, "QQ 授权失败", Toast.LENGTH_SHORT).show()
                    onFallback()
                }

                override fun onCancel() {
                    AppLogger.d(TAG, "qq login cancelled")
                    onFallback()
                }

                override fun onWarning(code: Int) {
                    AppLogger.w(TAG, "qq login warning: $code")
                }
            })
        }
    }

    private fun doShare(
        activity: android.app.Activity,
        params: Bundle,
        onFallback: () -> Unit
    ) {
        try {
            val tencent = QqShare.obtainTencent(activity)
            val qqShare = QQShare(activity, tencent.qqToken)
            qqShare.shareToQQ(activity, params, object : com.tencent.tauth.IUiListener {
                override fun onComplete(response: Any?) {
                    AppLogger.d(TAG, "qq share complete: $response")
                    Toast.makeText(activity, "已分享", Toast.LENGTH_SHORT).show()
                }

                override fun onError(e: com.tencent.tauth.UiError?) {
                    AppLogger.e(
                        TAG,
                        "qq share error: code=${e?.errorCode} msg=${e?.errorMessage} detail=${e?.errorDetail}"
                    )
                    // errorCode=-6 用户未授权（异常路径，理论上 loginAndShare 已处理）
                    // 其他错误码：网络/参数问题，fallback 到系统分享
                    if (e?.errorCode == QQ_ERR_USER_NOT_AUTHORIZED) {
                        Toast.makeText(activity, "请先授权 QQ 登录", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "分享失败，使用系统分享", Toast.LENGTH_SHORT).show()
                    }
                    onFallback()
                }

                override fun onCancel() {
                    AppLogger.d(TAG, "qq share cancelled")
                }

                override fun onWarning(code: Int) {
                    AppLogger.w(TAG, "qq share warning: $code")
                }
            })
        } catch (e: Exception) {
            AppLogger.e(TAG, "qq share sync error", e)
            onFallback()
        }
    }

    companion object {
        private const val TAG = "QqSdkSharer"
    }
}