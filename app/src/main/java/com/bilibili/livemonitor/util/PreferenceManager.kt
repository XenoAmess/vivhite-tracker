package com.bilibili.livemonitor.util

import android.content.Context
import android.content.SharedPreferences
import com.bilibili.livemonitor.domain.LiveStateDecider

class PreferenceManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

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
            .putLong(KEY_MONITORING_HEARTBEAT_TIME, 0L)
            .putLong(KEY_MONITORING_HEARTBEAT_GENERATION, next)
            .putBoolean(KEY_SERVICE_RUNNING, true)
            .apply()
        return next
    }

    fun getMonitoringGeneration(): Long = prefs.getLong(KEY_MONITORING_GENERATION, 0L)

    fun setMonitoringHeartbeat(timeMillis: Long, generation: Long) {
        prefs.edit()
            .putLong(KEY_MONITORING_HEARTBEAT_TIME, timeMillis)
            .putLong(KEY_MONITORING_HEARTBEAT_GENERATION, generation)
            .apply()
    }

    fun getMonitoringHeartbeatTime(): Long = prefs.getLong(KEY_MONITORING_HEARTBEAT_TIME, 0L)

    fun getMonitoringHeartbeatGeneration(): Long =
        prefs.getLong(KEY_MONITORING_HEARTBEAT_GENERATION, 0L)

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

    // 最后一次「确认在播」的检测时间（ms，每次 Live 检测写入）。
    // 注意与 lastCheckTime 区分：后者每次检测（含 NotLive）都覆盖，
    // 进程死亡后补闭合场次时只能以本字段作为"存活证据上限"
    fun setLastLiveObservedTime(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_LIVE_OBSERVED_TIME, timeMillis).apply()
    }

    fun getLastLiveObservedTime(): Long {
        return prefs.getLong(KEY_LAST_LIVE_OBSERVED_TIME, 0L)
    }

    // 直播检测间隔（秒）：省电 300 / 标准 60（默认）/ 实时 15
    fun setCheckIntervalSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_CHECK_INTERVAL_SECONDS, seconds).apply()
    }

    fun getCheckIntervalSeconds(): Int {
        return prefs.getInt(KEY_CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_STANDARD_SECONDS)
    }

    // 自动备份：开关 + SAF 树目录 uri + 上次备份时间
    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled).apply()
    }

    fun isAutoBackupEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)

    fun setBackupTreeUri(uri: String) {
        prefs.edit().putString(KEY_BACKUP_TREE_URI, uri).apply()
    }

    fun getBackupTreeUri(): String = prefs.getString(KEY_BACKUP_TREE_URI, "") ?: ""

    fun setLastBackupTime(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_BACKUP_TIME, timeMillis).apply()
    }

    fun getLastBackupTime(): Long = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)

    fun setOemBackgroundConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean(KEY_OEM_BACKGROUND_CONFIRMED, confirmed).apply()
    }

    fun isOemBackgroundConfirmed(): Boolean =
        prefs.getBoolean(KEY_OEM_BACKGROUND_CONFIRMED, false)

    // 月初海报去重：上次生成的月份（"yyyy-MM"，空 = 从未生成）
    fun setLastPosterMonth(monthKey: String) {
        prefs.edit().putString(KEY_LAST_POSTER_MONTH, monthKey).apply()
    }

    fun getLastPosterMonth(): String = prefs.getString(KEY_LAST_POSTER_MONTH, "") ?: ""

    // ============ 全量备份快照（FullBackup prefs.json） ============

    /** 魔法期 + 全部设置项 → JSON 字符串（备份用） */
    fun exportSnapshot(): String {
        val o = org.json.JSONObject()
        o.put("magic_periods", getMagicPeriodsJson())
        o.put("quiet_enabled", isQuietHoursEnabled())
        o.put("quiet_start", getQuietStartMinutes())
        o.put("quiet_end", getQuietEndMinutes())
        o.put("check_interval_seconds", getCheckIntervalSeconds())
        o.put("dark_mode", getDarkMode())
        o.put("monitor_videos", isMonitorVideos())
        o.put("monitor_dynamics", isMonitorDynamics())
        o.put("monitor_pinned", isMonitorPinned())
        o.put("dynamic_types", org.json.JSONArray(getMonitorDynamicTypes().toList()))
        o.put("alert_ring_on_activity", isAlertRingOnActivity())
        o.put("notify_stream_end", isNotifyStreamEnd())
        o.put("notify_title_change", isNotifyTitleChange())
        o.put("auto_check_update", isAutoCheckUpdate())
        o.put("auto_download_update", isAutoDownloadUpdate())
        o.put("alert_sound_uri", getAlertSoundUri())
        o.put("alert_sound_title", getAlertSoundTitle())
        o.put("auto_backup_enabled", isAutoBackupEnabled())
        o.put("backup_tree_uri", getBackupTreeUri())
        return o.toString()
    }

    data class SnapshotImportResult(
        val imported: Boolean,
        val magicPeriodsImported: Boolean
    )

    /** 从快照 JSON 恢复设置（导入用；缺失键保持现状不动）。 */
    fun importSnapshot(json: String): SnapshotImportResult {
        return try {
            val o = org.json.JSONObject(json)
            val magicPeriods = if (o.has("magic_periods")) {
                val value = o.get("magic_periods")
                when (value) {
                    is org.json.JSONArray -> value
                    is String -> org.json.JSONArray(value)
                    else -> throw org.json.JSONException("magic_periods must be an array")
                }.toString()
            } else null
            val quietStart = o.optIntOrNull("quiet_start")?.also { require(it in 0..1439) }
            val quietEnd = o.optIntOrNull("quiet_end")?.also { require(it in 0..1439) }
            val interval = o.optIntOrNull("check_interval_seconds")?.also {
                require(it in setOf(CHECK_INTERVAL_REALTIME_SECONDS, CHECK_INTERVAL_STANDARD_SECONDS, CHECK_INTERVAL_ECO_SECONDS))
            }
            val darkMode = o.optIntOrNull("dark_mode")?.also {
                require(it in setOf(DARK_MODE_SYSTEM, DARK_MODE_LIGHT, DARK_MODE_DARK))
            }
            val dynamicTypes = if (o.has("dynamic_types")) {
                val arr = o.getJSONArray("dynamic_types")
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } else null
            val hasBackupUri = o.has("backup_tree_uri")
            val hasAutoBackup = o.has("auto_backup_enabled")
            val backupUri = o.optStringOrNull("backup_tree_uri")
            val backupUriUsable = !backupUri.isNullOrBlank() &&
                appContext.contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == backupUri && it.isWritePermission
                }

            val editor = prefs.edit()
            magicPeriods?.let { editor.putString(KEY_MAGIC_PERIODS, it) }
            o.optBooleanOrNull("quiet_enabled")?.let { editor.putBoolean(KEY_QUIET_HOURS_ENABLED, it) }
            quietStart?.let { editor.putInt(KEY_QUIET_START_MINUTES, it) }
            quietEnd?.let { editor.putInt(KEY_QUIET_END_MINUTES, it) }
            interval?.let { editor.putInt(KEY_CHECK_INTERVAL_SECONDS, it) }
            darkMode?.let { editor.putInt(KEY_DARK_MODE, it) }
            o.optBooleanOrNull("monitor_videos")?.let { editor.putBoolean(KEY_MONITOR_VIDEOS, it) }
            o.optBooleanOrNull("monitor_dynamics")?.let { editor.putBoolean(KEY_MONITOR_DYNAMICS, it) }
            o.optBooleanOrNull("monitor_pinned")?.let { editor.putBoolean(KEY_MONITOR_PINNED, it) }
            dynamicTypes?.let { editor.putStringSet(KEY_MONITOR_DYNAMIC_TYPES, it) }
            o.optBooleanOrNull("alert_ring_on_activity")?.let { editor.putBoolean(KEY_ALERT_RING_ON_ACTIVITY, it) }
            o.optBooleanOrNull("notify_stream_end")?.let { editor.putBoolean(KEY_NOTIFY_STREAM_END, it) }
            o.optBooleanOrNull("notify_title_change")?.let { editor.putBoolean(KEY_NOTIFY_TITLE_CHANGE, it) }
            o.optBooleanOrNull("auto_check_update")?.let { editor.putBoolean(KEY_AUTO_CHECK_UPDATE, it) }
            o.optBooleanOrNull("auto_download_update")?.let { editor.putBoolean(KEY_AUTO_DOWNLOAD_UPDATE, it) }
            o.optStringOrNull("alert_sound_uri")?.let { editor.putString(KEY_ALERT_SOUND_URI, it) }
            o.optStringOrNull("alert_sound_title")?.let { editor.putString(KEY_ALERT_SOUND_TITLE, it) }
            if (hasBackupUri) {
                editor.putString(KEY_BACKUP_TREE_URI, backupUri.takeIf { backupUriUsable }.orEmpty())
                if (hasAutoBackup) {
                    editor.putBoolean(KEY_AUTO_BACKUP_ENABLED, backupUriUsable && o.getBoolean("auto_backup_enabled"))
                } else if (!backupUriUsable) {
                    editor.putBoolean(KEY_AUTO_BACKUP_ENABLED, false)
                }
            } else if (hasAutoBackup) {
                val currentUri = getBackupTreeUri()
                val currentUriUsable = currentUri.isNotBlank() &&
                    appContext.contentResolver.persistedUriPermissions.any {
                        it.uri.toString() == currentUri && it.isWritePermission
                    }
                editor.putBoolean(
                    KEY_AUTO_BACKUP_ENABLED,
                    currentUriUsable && o.getBoolean("auto_backup_enabled")
                )
            }
            val imported = editor.commit()
            SnapshotImportResult(imported = imported, magicPeriodsImported = imported && magicPeriods != null)
        } catch (e: Exception) {
            AppLogger.w("PreferenceManager", "importSnapshot failed", e)
            SnapshotImportResult(imported = false, magicPeriodsImported = false)
        }
    }

    private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
        if (has(key)) getInt(key) else null

    private fun org.json.JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key)) getBoolean(key) else null

    private fun org.json.JSONObject.optStringOrNull(key: String): String? =
        if (has(key)) getString(key) else null

    // 监控健康度：检测记录环形缓冲（JSON，最近 500 条）
    @Synchronized
    fun appendCheckRecord(ts: Long, success: Boolean, isLive: Boolean, reason: String) {
        val arr = getCheckRecordsJson()
        arr.put(org.json.JSONObject().apply {
            put("ts", ts)
            put("ok", success)
            put("live", isLive)
            put("r", reason)
        })
        while (arr.length() > CHECK_RECORDS_CAP) arr.remove(0)
        prefs.edit().putString(KEY_CHECK_RECORDS, arr.toString()).apply()
    }

    fun getCheckRecordsJson(): org.json.JSONArray {
        return try {
            org.json.JSONArray(prefs.getString(KEY_CHECK_RECORDS, "[]") ?: "[]")
        } catch (e: Exception) {
            org.json.JSONArray()
        }
    }

    /** 环形 JSON → 结构列表（domain MonitorHealth 的 CheckRecord） */
    fun getCheckRecords(): List<com.bilibili.livemonitor.domain.MonitorHealth.CheckRecord> {
        val arr = getCheckRecordsJson()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            com.bilibili.livemonitor.domain.MonitorHealth.CheckRecord(
                ts = o.optLong("ts"),
                success = o.optBoolean("ok"),
                isLive = o.optBoolean("live"),
                reason = o.optString("r")
            )
        }
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

    fun setLastUpdateAttemptTime(timeMillis: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_ATTEMPT_TIME, timeMillis).apply()
    }

    fun getLastUpdateAttemptTime(): Long =
        prefs.getLong(KEY_LAST_UPDATE_ATTEMPT_TIME, 0L)

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

    // 勿扰窗口内被静音的开播时间戳（0=无），勿扰结束后补一条「错过提醒」汇总
    fun setQuietMissedLiveTs(ts: Long) {
        prefs.edit().putLong(KEY_QUIET_MISSED_LIVE_TS, ts).apply()
    }

    fun getQuietMissedLiveTs(): Long {
        return prefs.getLong(KEY_QUIET_MISSED_LIVE_TS, 0L)
    }

    // 勿扰窗口内被静音的开播标题（可能为空，汇总时兜底文案）
    fun setQuietMissedLiveTitle(title: String) {
        prefs.edit().putString(KEY_QUIET_MISSED_LIVE_TITLE, title).apply()
    }

    fun getQuietMissedLiveTitle(): String {
        return prefs.getString(KEY_QUIET_MISSED_LIVE_TITLE, "") ?: ""
    }

    // ========== 直播生命周期提醒 ==========

    // 下播时通知（含时长），默认开
    fun setNotifyStreamEnd(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_STREAM_END, enabled).apply()
    }

    fun isNotifyStreamEnd(): Boolean {
        return prefs.getBoolean(KEY_NOTIFY_STREAM_END, true)
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

    // ========== 外观 ==========

    // 深色模式：0=跟随系统 1=浅色 2=深色
    fun setDarkMode(mode: Int) {
        prefs.edit().putInt(KEY_DARK_MODE, mode).apply()
    }

    fun getDarkMode(): Int {
        return prefs.getInt(KEY_DARK_MODE, DARK_MODE_SYSTEM)
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
        private const val KEY_MONITORING_HEARTBEAT_TIME = "monitoring_heartbeat_time"
        private const val KEY_MONITORING_HEARTBEAT_GENERATION = "monitoring_heartbeat_generation"
        private const val KEY_OEM_GUIDE_PROMPTED = "oem_guide_prompted"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val DEFAULT_ROOM_ID = com.bilibili.livemonitor.util.BiliTargets.ROOM_ID

        // ===== 直播检测状态（Worker/Service/UI 共享）=====
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_LAST_CHECK_LIVE = "last_check_live"
        private const val KEY_LAST_CHECK_SUCCESS = "last_check_success"
        private const val KEY_ALERT_SUPPRESSED = "alert_suppressed"
        private const val KEY_LAST_LIVE_START_TIME = "last_live_start_time"
        private const val KEY_LAST_LIVE_OBSERVED_TIME = "last_live_observed_time"
        private const val KEY_CHECK_INTERVAL_SECONDS = "check_interval_seconds"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_POSTER_MONTH = "last_poster_month"
        private const val KEY_OEM_BACKGROUND_CONFIRMED = "oem_background_confirmed"
        private const val KEY_CHECK_RECORDS = "check_records"
        // 15 秒档一天最多 5760 个周期，留出手动检查与时间漂移余量。
        private const val CHECK_RECORDS_CAP = 6_000

        // 检测频率档位（秒）
        const val CHECK_INTERVAL_ECO_SECONDS = 300
        const val CHECK_INTERVAL_STANDARD_SECONDS = 60
        const val CHECK_INTERVAL_REALTIME_SECONDS = 15
        private const val KEY_SUPPRESSED_LIVE_START = "suppressed_live_start"

        // ===== 应用更新检查 =====
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_AUTO_DOWNLOAD_UPDATE = "auto_download_update"
        private const val KEY_LAST_UPDATE_CHECK_TIME = "last_update_check_time"
        private const val KEY_LAST_UPDATE_ATTEMPT_TIME = "last_update_attempt_time"
        private const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"

        // ===== 提醒铃声 =====
        private const val KEY_ALERT_SOUND_URI = "alert_sound_uri"
        private const val KEY_ALERT_SOUND_TITLE = "alert_sound_title"

        // ===== 勿扰时段 =====
        private const val KEY_QUIET_HOURS_ENABLED = "quiet_hours_enabled"
        private const val KEY_QUIET_START_MINUTES = "quiet_start_minutes"
        private const val KEY_QUIET_END_MINUTES = "quiet_end_minutes"
        private const val KEY_QUIET_MISSED_LIVE_TS = "quiet_missed_live_ts"
        private const val KEY_QUIET_MISSED_LIVE_TITLE = "quiet_missed_live_title"
        private const val DEFAULT_QUIET_START_MINUTES = 23 * 60
        private const val DEFAULT_QUIET_END_MINUTES = 7 * 60

        // ===== 直播生命周期提醒 =====
        private const val KEY_NOTIFY_STREAM_END = "notify_stream_end"
        private const val KEY_LAST_REMINDED_LIVE_DYNAMIC_ID = "last_reminded_live_dynamic_id"

        // ===== 直播主题变化提醒 =====
        private const val KEY_NOTIFY_TITLE_CHANGE = "notify_title_change"
        private const val KEY_LAST_LIVE_TITLE = "last_live_title"

        // ===== 外观 =====
        private const val KEY_DARK_MODE = "dark_mode"
        const val DARK_MODE_SYSTEM = 0
        const val DARK_MODE_LIGHT = 1
        const val DARK_MODE_DARK = 2

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
