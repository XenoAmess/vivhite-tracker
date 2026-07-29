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

    /**
     * 把 MainActivity.onActivityResult 收到的系统回调转发给 QQ SDK。
     * SDK 内部用 UIListenerManager 把数据 dispatch 给当前 login 的 IUiListener。
     * MainActivity 必须 override onActivityResult 并调本方法。
     * @param requestCode 系统回调 requestCode
     * @param resultCode 系统回调 resultCode
     * @param data 系统回调 data Intent（可能为 null）
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
}

class DefaultQqSdkSharer : QqSdkSharer {
    private val contextRef = java.util.concurrent.atomic.AtomicReference<android.content.Context>()
    private var currentLoginListener: com.tencent.tauth.IUiListener? = null

    companion object {
        private const val PREFS_NAME = "qq_share_state"
        private const val KEY_AUTHORIZED = "authorized"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val TAG = "QqSdkSharer"
    }

    private fun isManuallyAuthorized(): Boolean {
        val ctx = contextRef.get() ?: return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val authorized = prefs.getBoolean(KEY_AUTHORIZED, false)
        if (!authorized) return false
        // 检查过期（默认 60 天，与 SDK 的 5184000s 对齐）
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (System.currentTimeMillis() > expiresAt) {
            prefs.edit().clear().apply()
            return false
        }
        return true
    }

    private fun setManuallyAuthorized(expiresInSeconds: Long) {
        val ctx = contextRef.get() ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_AUTHORIZED, true)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000)
            .apply()
        AppLogger.d(TAG, "manual authorized until ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + expiresInSeconds * 1000))}")
    }

    override fun isAuthorized(): Boolean {
        // 先查持久化标记（跨进程重启有效）
        if (isManuallyAuthorized()) return true
        val ctx = contextRef.get() ?: return false
        return try {
            val valid = QqShare.obtainTencent(ctx).isSessionValid
            AppLogger.d(TAG, "isAuthorized: sessionValid=$valid")
            valid
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
        val tencent = QqShare.obtainTencent(activity)
        // 绕 SDK 内部 "permission not granted" 提前 -6 限制：
        // 绕 SDK 内部 "permission not granted" 提前 -6 限制：
        // SDK 3.5.19 的 Tencent.isPermissionNotGranted() 首次跑（shared_prefs 无 build_model）
        // 会立即返回 true → tencent.login() 第一步就回调 -6，根本不弹 QQ 授权页。
        // setIsPermissionGranted(true) 同时设 static d=false 并把 build_model 写进 shared_prefs，
        // 之后 isPermissionNotGranted() 直接走 d==false 分支返回 false，正常走 login 流程。
        // 公开 API（com.tencent.tauth.Tencent），不涉及任何 hack。
        com.tencent.tauth.Tencent.setIsPermissionGranted(
            true,
            android.os.Build.MODEL
        )
        AppLogger.d(TAG, "qq requesting login")
        // 必须持有 IUiListener 引用：AssistActivity.setResult 后通过
        // Tencent.onActivityResultData(reqCode, resultCode, data, listener) 转发回调。
        // listener 持有 → GC 不回收 → onComplete / onError 能被调到
        currentLoginListener = object : com.tencent.tauth.IUiListener {
            override fun onComplete(response: Any?) {
                AppLogger.d(TAG, "qq login complete: $response")
                // 持久化授权状态 + 过期时间（跨进程重启有效）
                val json = org.json.JSONObject(response?.toString() ?: "{}")
                setManuallyAuthorized(json.optLong("expires_in", 5184000L))
                currentLoginListener = null
                onAuthorized()
            }

            override fun onError(e: com.tencent.tauth.UiError?) {
                AppLogger.e(TAG, "qq login error: ${e?.errorCode} ${e?.errorMessage}")
                currentLoginListener = null
                onError(e?.errorCode ?: -1, e?.errorMessage)
            }

            override fun onCancel() {
                AppLogger.d(TAG, "qq login cancelled")
                currentLoginListener = null
                onCancelled()
            }

            override fun onWarning(code: Int) {
                AppLogger.w(TAG, "qq login warning: $code")
            }
        }
        tencent.login(activity, "all", currentLoginListener!!)
    }

    /**
     * 供 MainActivity.onActivityResult 调用，把系统回调转发给 QQ SDK。
     * 公开 API（com.tencent.tauth.Tencent.onActivityResultData），
     * SDK 内部会通过 UIListenerManager 把数据 dispatch 给 currentLoginListener。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val listener = currentLoginListener
        if (listener != null) {
            com.tencent.tauth.Tencent.onActivityResultData(
                requestCode, resultCode, data, listener
            )
        } else {
            AppLogger.w(TAG, "onActivityResult but no pending login listener")
        }
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
}

/** 错误码 -6 = 用户未授权（QQ 互联 SDK 标准定义） */
internal const val QQ_ERR_USER_NOT_AUTHORIZED = -6