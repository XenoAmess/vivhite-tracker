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
 * QQ 互联 SDK 分享调用的包装接口，便于测试注入 fake。
 *
 * 流程设计（2026-07 用户反馈审核通过后 -6 用户未授权）：
 * 1. UI 先调 [isAuthorized] 检查；未授权弹引导对话框，由用户决定"去授权"或"普通分享"
 * 2. 用户选"去授权"后，UI 调 [login] 弹 QQ 授权页；登录完成回调里自动调 [shareToQQ]
 * 3. 用户选"普通分享"或 shareToQQ 失败，UI 自行 fallback 到系统分享面板
 *
 * 错误码 -6 = 用户未授权（QQ 互联 SDK 标准定义）
 * 其他负数 = 网络/参数错误；正数 = 业务级错误
 */
interface QqSdkSharer {
    /**
     * 当前是否已授权 QQ 互联（持有有效 session）。
     * 引导对话框先调此决定 UI 走向。
     */
    fun isAuthorized(): Boolean

    /**
     * 弹 QQ 授权页；登录完成回调里 onAuthorized() 被调用。
     * @param onAuthorized 用户在 QQ 端完成授权后回调（UI 可继续走 shareToQQ）
     * @param onCancelled 用户在 QQ 授权页取消时回调
     * @param onError 授权过程出错时回调
     */
    fun login(
        activity: android.app.Activity,
        onAuthorized: () -> Unit,
        onCancelled: () -> Unit,
        onError: (errorCode: Int, message: String?) -> Unit
    )

    /**
     * 分享入口（已授权情况下直接调）。失败由调用方处理。
     * @param onComplete 分享成功回调
     * @param onCancel 分享取消
     * @param onError (errorCode, message) → -6 是 session 过期，UI 应重新弹授权引导
     */
    fun shareToQQ(
        activity: android.app.Activity,
        params: Bundle,
        onComplete: () -> Unit,
        onCancel: () -> Unit,
        onError: (errorCode: Int, message: String?) -> Unit
    )
}

class DefaultQqSdkSharer : QqSdkSharer {
    private val contextRef = java.util.concurrent.atomic.AtomicReference<android.content.Context>()

    override fun isAuthorized(): Boolean {
        val ctx = contextRef.get() ?: return false
        return try {
            QqShare.obtainTencent(ctx).isSessionValid
        } catch (e: Exception) {
            AppLogger.w(TAG, "isAuthorized check failed", e)
            false
        }
    }

    override fun login(
        activity: android.app.Activity,
        onAuthorized: () -> Unit,
        onCancelled: () -> Unit,
        onError: (errorCode: Int, message: String?) -> Unit
    ) {
        // login() 必须用 Activity 上下文（QQ 授权页是一个透明 Activity），
        // 之前用 applicationContext 强转 Activity 导致 ClassCastException 崩溃
        val tencent = QqShare.obtainTencent(activity)
        AppLogger.d(TAG, "qq requesting login")
        tencent.login(activity, "all", object : com.tencent.tauth.IUiListener {
            override fun onComplete(response: Any?) {
                AppLogger.d(TAG, "qq login complete: $response")
                onAuthorized()
            }

            override fun onError(e: com.tencent.tauth.UiError?) {
                AppLogger.e(TAG, "qq login error: ${e?.errorCode} ${e?.errorMessage}")
                onError(e?.errorCode ?: -1, e?.errorMessage)
            }

            override fun onCancel() {
                AppLogger.d(TAG, "qq login cancelled")
                onCancelled()
            }

            override fun onWarning(code: Int) {
                AppLogger.w(TAG, "qq login warning: $code")
            }
        })
    }

    override fun shareToQQ(
        activity: android.app.Activity,
        params: Bundle,
        onComplete: () -> Unit,
        onCancel: () -> Unit,
        onError: (errorCode: Int, message: String?) -> Unit
    ) {
        try {
            val tencent = QqShare.obtainTencent(activity)
            val qqShare = QQShare(activity, tencent.qqToken)
            qqShare.shareToQQ(activity, params, object : com.tencent.tauth.IUiListener {
                override fun onComplete(response: Any?) {
                    AppLogger.d(TAG, "qq share complete: $response")
                    onComplete()
                }

                override fun onError(e: com.tencent.tauth.UiError?) {
                    AppLogger.e(
                        TAG,
                        "qq share error: code=${e?.errorCode} msg=${e?.errorMessage} detail=${e?.errorDetail}"
                    )
                    onError(e?.errorCode ?: -1, e?.errorMessage)
                }

                override fun onCancel() {
                    AppLogger.d(TAG, "qq share cancelled")
                    onCancel()
                }

                override fun onWarning(code: Int) {
                    AppLogger.w(TAG, "qq share warning: $code")
                }
            })
        } catch (e: Exception) {
            AppLogger.e(TAG, "qq share sync error", e)
            onError(-1, e.message)
        }
    }

    init {
        // MainActivity 在 onCreate 时把 applicationContext 注入这里，
        // 用于 isAuthorized / login 调 Tencent.createInstance
        // 此处不主动 inject，由外部 (QqShare.bind) 触发
    }

    fun bind(context: android.content.Context) {
        contextRef.set(context.applicationContext)
    }

    companion object {
        private const val TAG = "QqSdkSharer"
    }
}

/** 错误码 -6 = 用户未授权（QQ 互联 SDK 标准定义） */
internal const val QQ_ERR_USER_NOT_AUTHORIZED = -6