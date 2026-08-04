package com.bilibili.livemonitor.util

/**
 * B 站监控目标常量（单一来源）：改房间号/UID 只改这里。
 * 直播间 11258892 = 白绮；UID 251990176 从 get_info.uid 查得。
 * 注：PromoImageRenderer 的绘制文案与 layout 里的展示文本仍是视觉内容，未纳入。
 */
object BiliTargets {
    const val ROOM_ID = 11258892L
    const val MONITOR_MID = 251990176L
}
