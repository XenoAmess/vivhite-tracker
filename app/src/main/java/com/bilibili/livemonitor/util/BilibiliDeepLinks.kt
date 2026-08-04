package com.bilibili.livemonitor.util

import android.content.Intent
import android.net.Uri

/**
 * B 站深链 Intent 纯工具：直播间/空间主页的 App 与 Web 两种形态。
 * MainActivity 的打开直播间/空间按钮共用，选择器模式（App 注入 EXTRA_INITIAL_INTENTS）由调用方组装。
 */
object BilibiliDeepLinks {

    /** bilibili:// 客户端深链；pkg 非空时 setPackage 强投递，绕开 resolveActivity 包可见性不确定性 */
    fun liveRoomAppIntent(roomId: Long, pkg: String?): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://live/$roomId")).apply {
            if (!pkg.isNullOrEmpty()) setPackage(pkg)
        }

    /** https 直播间；pkg 非空时强制用指定浏览器 */
    fun liveRoomWebIntent(roomId: Long, pkg: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/$roomId")).apply {
            if (!pkg.isNullOrEmpty()) setPackage(pkg)
        }

    /** bilibili:// 空间主页深链 */
    fun spaceAppIntent(mid: Long): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://space/$mid"))

    /** https 空间主页 */
    fun spaceWebIntent(mid: Long): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/$mid"))
}
