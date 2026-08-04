package com.bilibili.livemonitor.util

import android.os.Bundle
import android.content.Intent

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

    /**
     * QQ空间图文说说（SHARE_TO_QZONE_TYPE_IMAGE_TEXT：文案+本地图+链接）。
     * 这是图文进 QQ 系的官方通道——系统 ACTION_SEND 的 EXTRA_TEXT 必被丢弃，
     * 说说通道文案图片俱全。
     *
     * 接口扩容带默认实现：不破坏既有测试 fake（7 处 object : QqSdkSharer）；
     * 生产由 DefaultQqSdkSharer 覆盖。默认实现直接回 onError。
     */
    fun shareToQzone(
        activity: android.app.Activity,
        params: Bundle,
        onComplete: () -> Unit,
        onCancel: () -> Unit,
        onError: (errorCode: Int, message: String?) -> Unit
    ) {
        onError(-1, "shareToQzone not implemented")
    }
}

/** 错误码 -6 = 用户未授权（QQ 互联 SDK 标准定义） */
internal const val QQ_ERR_USER_NOT_AUTHORIZED = -6
