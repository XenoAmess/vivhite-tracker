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
     * - suppressed（观播静音）为 true 时恒不提醒：用户已进直播间观看，
     *   本场直播结束前不需要再响铃
     * - lastStatus == null（首次检查/状态未知）：在播就提醒
     * - 否则仅在 未开播→开播 跳变时提醒，避免重复打扰
     */
    fun shouldAlert(
        lastStatus: Boolean?,
        isLive: Boolean,
        suppressed: Boolean = false,
        isNewSession: Boolean = false
    ): Boolean {
        if (suppressed) return false
        return isLive && (lastStatus != true || isNewSession)
    }

    /** B 站场次标识只做首尾空白归一；空值不具备场次身份。 */
    fun normalizeLiveStartTime(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 持续 Live 时，仅两个明确且不同的场次标识才能证明已经换场。
     * 当前标识缺失时保持同场，避免网页兜底结果制造重复提醒。
     */
    fun isNewLiveSession(
        lastStatus: Boolean?,
        previousLiveStartTime: String?,
        currentLiveStartTime: String?
    ): Boolean {
        if (lastStatus != true) return false
        val previous = normalizeLiveStartTime(previousLiveStartTime) ?: return false
        val current = normalizeLiveStartTime(currentLiveStartTime) ?: return false
        return previous != current
    }

    /**
     * 观播静音何时解除：
     * - 下播（NotLive）即解除，之后下次开播恢复正常提醒
     * - 检测到【新一场直播】（live_start_time 与置静音时绑定的不一致）即解除——
     *   旧场景：置静音后服务在下播窗口期被杀，标记卡死，之后所有开播都被短路不响铃
     */
    fun shouldClearSuppression(isLive: Boolean, isNewSession: Boolean = false): Boolean {
        return !isLive || isNewSession
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

    fun isHeartbeatStale(
        heartbeatTime: Long,
        heartbeatGeneration: Long,
        monitoringGeneration: Long,
        now: Long,
        maxAgeMillis: Long
    ): Boolean {
        if (heartbeatTime <= 0L || heartbeatGeneration != monitoringGeneration) return true
        return now - heartbeatTime > maxAgeMillis
    }
}
