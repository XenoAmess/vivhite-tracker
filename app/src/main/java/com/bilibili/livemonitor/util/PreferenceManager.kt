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

    /**
     * 用户每次开始监控都进入新会话。STOP intent 带着当时的会话号，延迟到达时不会
     * 误杀已经重新开始的监控实例。
     */
    fun beginMonitoringSession(): Long {
        val next = maxOf(
            prefs.getLong(KEY_MONITORING_GENERATION, 0L) + 1,
            System.currentTimeMillis()
        )
        prefs.edit()
            .putLong(KEY_MONITORING_GENERATION, next)
            .putBoolean(KEY_SERVICE_RUNNING, true)
            .apply()
        return next
    }

    fun getMonitoringGeneration(): Long = prefs.getLong(KEY_MONITORING_GENERATION, 0L)

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

    // 最近一次检测到的本场直播 live_start_time（服务每次 Live 时写入，置静音时以此为绑定参照）
    fun setLastLiveStartTime(startTime: String) {
        prefs.edit().putString(KEY_LAST_LIVE_START_TIME, startTime).apply()
    }

    fun getLastLiveStartTime(): String {
        return prefs.getString(KEY_LAST_LIVE_START_TIME, "") ?: ""
    }

    // 观播静音绑定的本场直播 live_start_time；与当前不一致 = 新一场 = 自动解除静音
    // 空串 = 老标记（无绑定信息），不参与新会话比对
    fun setSuppressedLiveStart(startTime: String) {
        prefs.edit().putString(KEY_SUPPRESSED_LIVE_START, startTime).apply()
    }

    fun getSuppressedLiveStart(): String {
        return prefs.getString(KEY_SUPPRESSED_LIVE_START, "") ?: ""
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

    // 宣传图风格选择（生成宣传图预览对话框里切换，记住上次选择）
    fun setPromoStyle(style: String) {
        prefs.edit().putString(KEY_PROMO_STYLE, style).apply()
    }

    fun getPromoStyle(): String {
        return prefs.getString(KEY_PROMO_STYLE, DEFAULT_PROMO_STYLE) ?: DEFAULT_PROMO_STYLE
    }

    // 魔法期记录：JSON 数组 [{start,end}]，ms，可为过去/未来
    fun setMagicPeriodsJson(json: String) {
        prefs.edit().putString(KEY_MAGIC_PERIODS, json).apply()
    }

    fun getMagicPeriodsJson(): String {
        return prefs.getString(KEY_MAGIC_PERIODS, "[]") ?: "[]"
    }

    // ========== 勿扰时段 ==========

    // 勿扰总开关，默认关闭（用户手动开启）
    fun setQuietHoursEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUIET_HOURS_ENABLED, enabled).apply()
    }

    fun isQuietHoursEnabled(): Boolean {
        return prefs.getBoolean(KEY_QUIET_HOURS_ENABLED, false)
    }

    // 勿扰开始（距 0 点分钟数，默认 23:00）
    fun setQuietStartMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_QUIET_START_MINUTES, minutes).apply()
    }

    fun getQuietStartMinutes(): Int {
        return prefs.getInt(KEY_QUIET_START_MINUTES, DEFAULT_QUIET_START_MINUTES)
    }

    // 勿扰结束（距 0 点分钟数，默认 07:00）
    fun setQuietEndMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_QUIET_END_MINUTES, minutes).apply()
    }

    fun getQuietEndMinutes(): Int {
        return prefs.getInt(KEY_QUIET_END_MINUTES, DEFAULT_QUIET_END_MINUTES)
    }

    // ========== 直播生命周期提醒 ==========

    // 下播时通知（含时长），默认开
    fun setNotifyStreamEnd(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_STREAM_END, enabled).apply()
    }

    fun isNotifyStreamEnd(): Boolean {
        return prefs.getBoolean(KEY_NOTIFY_STREAM_END, true)
    }

    // 最近一次下播时间戳（回放上线提醒窗口基准）
    fun setLastStreamEndTs(ts: Long) {
        prefs.edit().putLong(KEY_LAST_STREAM_END_TS, ts).apply()
    }

    fun getLastStreamEndTs(): Long {
        return prefs.getLong(KEY_LAST_STREAM_END_TS, 0L)
    }

    // 已提醒过的开播预告动态 id（按 id_str 去重）
    fun setLastRemindedLiveDynamicId(id: String) {
        prefs.edit().putString(KEY_LAST_REMINDED_LIVE_DYNAMIC_ID, id).apply()
    }

    fun getLastRemindedLiveDynamicId(): String {
        return prefs.getString(KEY_LAST_REMINDED_LIVE_DYNAMIC_ID, "") ?: ""
    }

    // ========== 直播主题变化提醒 ==========

    // 开播中标题变化时提醒，默认关
    fun setNotifyTitleChange(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_TITLE_CHANGE, enabled).apply()
    }

    fun isNotifyTitleChange(): Boolean {
        return prefs.getBoolean(KEY_NOTIFY_TITLE_CHANGE, false)
    }

    // 上次见到的直播标题（变化判定基线）
    fun setLastLiveTitle(title: String) {
        prefs.edit().putString(KEY_LAST_LIVE_TITLE, title).apply()
    }

    fun getLastLiveTitle(): String {
        return prefs.getString(KEY_LAST_LIVE_TITLE, "") ?: ""
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

    // 动态类型过滤（勾选的类型才提醒），默认全开
    fun setMonitorDynamicTypes(types: Set<String>) {
        prefs.edit().putStringSet(KEY_MONITOR_DYNAMIC_TYPES, types).apply()
    }

    fun getMonitorDynamicTypes(): Set<String> {
        return prefs.getStringSet(KEY_MONITOR_DYNAMIC_TYPES, DEFAULT_DYNAMIC_TYPES) ?: DEFAULT_DYNAMIC_TYPES
    }

    fun isDynamicTypeEnabled(type: String): Boolean =
        type in getMonitorDynamicTypes()

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

        // ===== 监控目标与运行开关 =====
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_MONITORING_GENERATION = "monitoring_generation"
        private const val KEY_OEM_GUIDE_PROMPTED = "oem_guide_prompted"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val DEFAULT_ROOM_ID = com.bilibili.livemonitor.util.BiliTargets.ROOM_ID

        // ===== 直播检测状态（Worker/Service/UI 共享）=====
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_LAST_CHECK_LIVE = "last_check_live"
        private const val KEY_LAST_CHECK_SUCCESS = "last_check_success"
        private const val KEY_ALERT_SUPPRESSED = "alert_suppressed"
        private const val KEY_LAST_LIVE_START_TIME = "last_live_start_time"
        private const val KEY_SUPPRESSED_LIVE_START = "suppressed_live_start"

        // ===== 应用更新检查 =====
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_AUTO_DOWNLOAD_UPDATE = "auto_download_update"
        private const val KEY_LAST_UPDATE_CHECK_TIME = "last_update_check_time"
        private const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"

        // ===== 提醒铃声 =====
        private const val KEY_ALERT_SOUND_URI = "alert_sound_uri"
        private const val KEY_ALERT_SOUND_TITLE = "alert_sound_title"

        // ===== 勿扰时段 =====
        private const val KEY_QUIET_HOURS_ENABLED = "quiet_hours_enabled"
        private const val KEY_QUIET_START_MINUTES = "quiet_start_minutes"
        private const val KEY_QUIET_END_MINUTES = "quiet_end_minutes"
        private const val DEFAULT_QUIET_START_MINUTES = 23 * 60
        private const val DEFAULT_QUIET_END_MINUTES = 7 * 60

        // ===== 直播生命周期提醒 =====
        private const val KEY_NOTIFY_STREAM_END = "notify_stream_end"
        private const val KEY_LAST_STREAM_END_TS = "last_stream_end_ts"
        private const val KEY_LAST_REMINDED_LIVE_DYNAMIC_ID = "last_reminded_live_dynamic_id"

        // ===== 直播主题变化提醒 =====
        private const val KEY_NOTIFY_TITLE_CHANGE = "notify_title_change"
        private const val KEY_LAST_LIVE_TITLE = "last_live_title"

        // ===== B 站全活动监控 =====
        private const val KEY_MONITOR_VIDEOS = "monitor_videos"
        private const val KEY_MONITOR_PINNED = "monitor_pinned"
        private const val KEY_MONITOR_DYNAMICS = "monitor_dynamics"
        private const val KEY_ALERT_RING_ON_ACTIVITY = "alert_ring_on_activity"
        private const val KEY_LAST_VIDEO_AID = "last_video_aid"
        private const val KEY_LAST_PINNED_AID = "last_pinned_aid"
        private const val KEY_LAST_DYNAMIC_ID = "last_dynamic_id"
        private const val KEY_MONITOR_DYNAMIC_TYPES = "monitor_dynamic_types"
        private val DEFAULT_DYNAMIC_TYPES = setOf(
            "DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_FORWARD", "DYNAMIC_TYPE_ARTICLE"
        )

        // ===== 宣传图 / 魔法期 =====
        private const val KEY_PROMO_STYLE = "promo_style"
        private const val DEFAULT_PROMO_STYLE = "LIGHT_CARD"
        private const val KEY_MAGIC_PERIODS = "magic_periods"
    }
}
