package com.bilibili.livemonitor.util

import android.content.Context
import android.content.SharedPreferences
import com.bilibili.livemonitor.domain.LiveStateDecider

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveRoomId(roomId: Long) {
        prefs.edit().putLong(KEY_ROOM_ID, roomId).apply()
    }

    fun getRoomId(): Long {
        return prefs.getLong(KEY_ROOM_ID, DEFAULT_ROOM_ID)
    }

    fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }

    fun isServiceRunning(): Boolean {
        return prefs.getBoolean(KEY_SERVICE_RUNNING, false)
    }

    fun setLastCheck(timeMillis: Long, isLive: Boolean, success: Boolean) {
        prefs.edit()
            .putLong(KEY_LAST_CHECK_TIME, timeMillis)
            .putBoolean(KEY_LAST_CHECK_LIVE, isLive)
            .putBoolean(KEY_LAST_CHECK_SUCCESS, success)
            .apply()
    }

    fun getLastCheckTime(): Long {
        return prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
    }

    fun isLastCheckLive(): Boolean {
        return prefs.getBoolean(KEY_LAST_CHECK_LIVE, false)
    }

    fun isLastCheckSuccess(): Boolean {
        return prefs.getBoolean(KEY_LAST_CHECK_SUCCESS, false)
    }

    // 国产 ROM 后台保活引导是否已弹过（厂商自启动设置状态无 API 可读，
    // 只能记录"已引导"并依赖主界面按钮作为再入 口）
    fun setOemGuidePrompted(prompted: Boolean) {
        prefs.edit().putBoolean(KEY_OEM_GUIDE_PROMPTED, prompted).apply()
    }

    fun isOemGuidePrompted(): Boolean {
        return prefs.getBoolean(KEY_OEM_GUIDE_PROMPTED, false)
    }

    // 观播静音：点"打开直播间"后置位，本场直播结束前不提醒，下播自动解除
    fun setAlertSuppressed(suppressed: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_SUPPRESSED, suppressed).apply()
    }

    fun isAlertSuppressed(): Boolean {
        return prefs.getBoolean(KEY_ALERT_SUPPRESSED, false)
    }

    // 首次启动标记：控制邓煜名言首启必出
    fun setFirstLaunchDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, done).apply()
    }

    fun isFirstLaunchDone(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
    }

    // 自动检查更新（前台每日一次），默认开
    fun setAutoCheckUpdate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, enabled).apply()
    }

    fun isAutoCheckUpdate(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
    }

    // Wi-Fi 下自动下载更新包，默认关
    fun setAutoDownloadUpdate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_UPDATE, enabled).apply()
    }

    fun isAutoDownloadUpdate(): Boolean {
        return prefs.getBoolean(KEY_AUTO_DOWNLOAD_UPDATE, false)
    }

    // 上次更新检查时间，用于 24h 节流
    fun setLastUpdateCheckTime(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_TIME, timeMillis).apply()
    }

    fun getLastUpdateCheckTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)
    }

    // 用户点了「忽略此版本」的 versionCode，自动检测不再弹；-1 表示无
    fun setDismissedVersionCode(code: Int) {
        prefs.edit().putInt(KEY_DISMISSED_VERSION_CODE, code).apply()
    }

    fun getDismissedVersionCode(): Int {
        return prefs.getInt(KEY_DISMISSED_VERSION_CODE, -1)
    }

    // 提醒铃声：带前缀的 uri 字符串（builtin:/system:/file:），空串 = 内置默认
    fun setAlertSoundUri(uri: String) {
        prefs.edit().putString(KEY_ALERT_SOUND_URI, uri).apply()
    }

    fun getAlertSoundUri(): String {
        return prefs.getString(KEY_ALERT_SOUND_URI, "") ?: ""
    }

    // 提醒铃声展示名（UI 显示用，避免只看到一串 content://）
    fun setAlertSoundTitle(title: String) {
        prefs.edit().putString(KEY_ALERT_SOUND_TITLE, title).apply()
    }

    fun getAlertSoundTitle(): String {
        return prefs.getString(KEY_ALERT_SOUND_TITLE, "") ?: ""
    }

    // ========== B 站全活动监控 ==========

    // 监控新视频投稿，默认开
    fun setMonitorVideos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR_VIDEOS, enabled).apply()
    }

    fun isMonitorVideos(): Boolean {
        return prefs.getBoolean(KEY_MONITOR_VIDEOS, true)
    }

    // 监控置顶视频变化，默认开
    fun setMonitorPinned(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR_PINNED, enabled).apply()
    }

    fun isMonitorPinned(): Boolean {
        return prefs.getBoolean(KEY_MONITOR_PINNED, true)
    }

    // 监控动态，默认开
    fun setMonitorDynamics(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR_DYNAMICS, enabled).apply()
    }

    fun isMonitorDynamics(): Boolean {
        return prefs.getBoolean(KEY_MONITOR_DYNAMICS, true)
    }

    // 新视频/动态时是否响铃（开播不受此控制，始终响铃），默认开
    fun setAlertRingOnActivity(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_RING_ON_ACTIVITY, enabled).apply()
    }

    fun isAlertRingOnActivity(): Boolean {
        return prefs.getBoolean(KEY_ALERT_RING_ON_ACTIVITY, true)
    }

    // 上次见到的最新视频 avid；-1 = 未初始化（首次不提醒）
    fun setLastVideoAid(aid: Long) {
        prefs.edit().putLong(KEY_LAST_VIDEO_AID, aid).apply()
    }

    fun getLastVideoAid(): Long {
        return prefs.getLong(KEY_LAST_VIDEO_AID, -1L)
    }

    // 上次见到的置顶视频 avid；-1 = 未初始化
    fun setLastPinnedAid(aid: Long) {
        prefs.edit().putLong(KEY_LAST_PINNED_AID, aid).apply()
    }

    fun getLastPinnedAid(): Long {
        return prefs.getLong(KEY_LAST_PINNED_AID, -1L)
    }

    // 上次见到的最新动态 id；空串 = 未初始化
    fun setLastDynamicId(id: String) {
        prefs.edit().putString(KEY_LAST_DYNAMIC_ID, id).apply()
    }

    fun getLastDynamicId(): String {
        return prefs.getString(KEY_LAST_DYNAMIC_ID, "") ?: ""
    }

    // 进程重启时恢复上次状态，避免重复提醒；超过10分钟视为过期（期间可能刚开播，应当提醒）
    fun getRecentLastStatus(maxAgeMillis: Long = 600_000L): Boolean? {
        return LiveStateDecider.restoreLastStatus(
            lastCheckTime = getLastCheckTime(),
            lastCheckSuccess = isLastCheckSuccess(),
            lastCheckLive = isLastCheckLive(),
            now = System.currentTimeMillis(),
            maxAgeMillis = maxAgeMillis
        )
    }

    companion object {
        private const val PREF_NAME = "bilibili_live_monitor"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_LAST_CHECK_LIVE = "last_check_live"
        private const val KEY_LAST_CHECK_SUCCESS = "last_check_success"
        private const val KEY_OEM_GUIDE_PROMPTED = "oem_guide_prompted"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val KEY_ALERT_SUPPRESSED = "alert_suppressed"
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_AUTO_DOWNLOAD_UPDATE = "auto_download_update"
        private const val KEY_LAST_UPDATE_CHECK_TIME = "last_update_check_time"
        private const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"
        private const val KEY_ALERT_SOUND_URI = "alert_sound_uri"
        private const val KEY_ALERT_SOUND_TITLE = "alert_sound_title"
        private const val KEY_MONITOR_VIDEOS = "monitor_videos"
        private const val KEY_MONITOR_PINNED = "monitor_pinned"
        private const val KEY_MONITOR_DYNAMICS = "monitor_dynamics"
        private const val KEY_ALERT_RING_ON_ACTIVITY = "alert_ring_on_activity"
        private const val KEY_LAST_VIDEO_AID = "last_video_aid"
        private const val KEY_LAST_PINNED_AID = "last_pinned_aid"
        private const val KEY_LAST_DYNAMIC_ID = "last_dynamic_id"
        private const val DEFAULT_ROOM_ID = 11258892L
    }
}
