package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.api.BilibiliApi

/**
 * 直播状态决策的纯逻辑，从 LiveCheckService / PreferenceManager 提取，
 * 与 Android 框架无关，可纯 JVM 单测。
 *
 * 每条规则都对应一个真实用户场景，见 LiveStateDeciderTest。
 */
object LiveStateDecider {

    /**
     * 是否触发开播提醒。
     * - lastStatus == null（首次检查/状态未知）：在播就提醒
     * - 否则仅在 未开播→开播 跳变时提醒，避免重复打扰
     */
    fun shouldAlert(lastStatus: Boolean?, isLive: Boolean): Boolean {
        return if (lastStatus == null) {
            isLive
        } else {
            !lastStatus && isLive
        }
    }

    /**
     * 进程重启时恢复上次状态。
     * 仅在最近一次"成功"检测距今 maxAgeMillis 内时恢复；
     * 过期返回 null（视为首次——死亡期间可能刚开播，此时应当重新提醒）。
     */
    fun restoreLastStatus(
        lastCheckTime: Long,
        lastCheckSuccess: Boolean,
        lastCheckLive: Boolean,
        now: Long,
        maxAgeMillis: Long
    ): Boolean? {
        if (lastCheckTime <= 0 || !lastCheckSuccess) return null
        if (now - lastCheckTime > maxAgeMillis) return null
        return lastCheckLive
    }

    /**
     * 检测结果是否需要重试：只有 Error（网络抖动/解析失败）才重试，
     * Live/NotLive 是确定结果，重试无意义。
     */
    fun shouldRetry(status: BilibiliApi.LiveStatus): Boolean {
        return status is BilibiliApi.LiveStatus.Error
    }
}
