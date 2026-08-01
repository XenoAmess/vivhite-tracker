package com.bilibili.livemonitor.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 魔法期结束闹钟排程：setExactAndAllowWhileIdle(RTC_WAKEUP)，
 * 精确闹钟权限未授予时降级 inexact，与 AlarmReceiver 同款容错。
 *
 * 只排「最近的未来结束」一个闹钟；无未来结束则取消。
 */
object MagicAlarmScheduler {

    private const val REQUEST_CODE = 2101
    private const val TAG = "MagicAlarmScheduler"

    fun schedule(context: Context, triggerAtMs: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = pendingIntent(context)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() -> {
                    AppLogger.w(TAG, "exact alarm not granted, fallback to inexact")
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
                else -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
            }
            AppLogger.d(TAG, "magic end alarm scheduled at $triggerAtMs")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "schedule magic alarm SecurityException", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "schedule magic alarm failed", e)
        }
    }

    fun cancel(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
            AppLogger.d(TAG, "magic end alarm cancelled")
        } catch (e: Exception) {
            AppLogger.e(TAG, "cancel magic alarm failed", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, com.bilibili.livemonitor.receiver.MagicEndReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
