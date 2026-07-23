package com.bilibili.livemonitor.util

import android.content.Context
import android.content.SharedPreferences

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

    companion object {
        private const val PREF_NAME = "bilibili_live_monitor"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_LAST_CHECK_LIVE = "last_check_live"
        private const val KEY_LAST_CHECK_SUCCESS = "last_check_success"
        private const val DEFAULT_ROOM_ID = 11258892L
    }
}
