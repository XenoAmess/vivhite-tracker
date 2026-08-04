package com.bilibili.livemonitor.util

import android.os.Bundle
import android.content.Intent
import com.tencent.connect.share.QQShare

/**
 * [QqSdkSharer] 的生产实现，包装腾讯 QQ 互联 SDK。
 */
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
        } catch (e: LinkageError) {
            // SDK 类损坏（NoClassDefFoundError 等，dex 异常/依赖断裂）时
            // 抛的是 Error 不是 Exception——不防御的话分享按钮直接崩 App
            AppLogger.w(TAG, "isAuthorized check failed with linkage error", e)
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

    override fun shareToQzone(
        activity: android.app.Activity,
        params: Bundle,
        onComplete: () -> Unit,
        onCancel: () -> Unit,
        onError: (errorCode: Int, message: String?) -> Unit
    ) {
        try {
            val tencent = QqShare.obtainTencent(activity)
            val qzoneShare = com.tencent.connect.share.QzoneShare(activity, tencent.qqToken)
            qzoneShare.shareToQzone(activity, params, object : com.tencent.tauth.IUiListener {
                override fun onComplete(response: Any?) {
                    AppLogger.d(TAG, "qzone share complete: $response")
                    onComplete()
                }

                override fun onError(e: com.tencent.tauth.UiError?) {
                    AppLogger.e(
                        TAG,
                        "qzone share error: code=${e?.errorCode} msg=${e?.errorMessage} detail=${e?.errorDetail}"
                    )
                    onError(e?.errorCode ?: -1, e?.errorMessage)
                }

                override fun onCancel() {
                    AppLogger.d(TAG, "qzone share cancelled")
                    onCancel()
                }

                override fun onWarning(code: Int) {
                    AppLogger.w(TAG, "qzone share warning: $code")
                }
            })
        } catch (e: Exception) {
            AppLogger.e(TAG, "qzone share sync error", e)
            onError(-1, e.message)
        }
    }

    init {
        // MainActivity 在 onCreate 时把 applicationContext 注入这里，
        // 用于 isAuthorized / login 调 Tencent.createInstance
        // 此处不主动 inject，由外部 DefaultQqSdkSharer.bind(context) 触发
    }

    fun bind(context: android.content.Context) {
        contextRef.set(context.applicationContext)
    }
}
