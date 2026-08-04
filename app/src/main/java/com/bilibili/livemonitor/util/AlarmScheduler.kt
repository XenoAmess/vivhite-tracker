package com.bilibili.livemonitor.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * 精确闹钟统一排程：setExactAndAllowWhileIdle(RTC_WAKEUP)，
 * Android 12+ 精确闹钟权限未授予时降级 inexact。
 *
 * LiveCheckService（60s 检测 + 5min 动态）、AlarmReceiver、MagicAlarmScheduler 共用，
 * 避免同款 SDK 分支逻辑复制多份、改一处漏三处。
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /**
     * 排一个 RTC_WAKEUP 闹钟。精确权限缺失时自动降级为 setAndAllowWhileIdle。
     * @param triggerAtMs 触发时间戳
     * @param pendingIntent 已构造好的 PendingIntent
     * @param tag 日志标识（调用方）
     */
    fun schedule(context: Context, triggerAtMs: Long, pendingIntent: PendingIntent, tag: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() -> {
                    AppLogger.w(TAG, "exact alarm not granted, fallback to inexact ($tag)")
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
                else -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
            }
            AppLogger.d(TAG, "$tag scheduled at $triggerAtMs")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "$tag SecurityException", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "$tag failed", e)
        }
    }
}
