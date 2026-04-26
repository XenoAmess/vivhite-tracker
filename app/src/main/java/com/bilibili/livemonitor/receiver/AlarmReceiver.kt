package com.bilibili.livemonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "AlarmReceiver onReceive")
        val preferenceManager = PreferenceManager(context)
        val shouldRun = preferenceManager.isServiceRunning()
        val isRunning = LiveCheckService.isRunning

        Log.d(TAG, "shouldRun=$shouldRun isRunning=$isRunning")

        if (shouldRun && !isRunning) {
            Log.d(TAG, "Service should run but not running, restarting...")
            try {
                val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                    putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service", e)
            }
        }

        // 重新调度下一次Alarm，形成循环心跳
        if (shouldRun) {
            scheduleNextAlarm(context)
        }
    }

    private fun scheduleNextAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, Intent(context, AlarmReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.d(TAG, "scheduleNextAlarm at $triggerAt")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleNextAlarm failed", e)
        }
    }

    companion object {
        private const val ALARM_INTERVAL = 5 * 60_000L // 5分钟
        private const val ALARM_REQUEST_CODE = 2001
        private const val TAG = "AlarmReceiver"
    }
}
