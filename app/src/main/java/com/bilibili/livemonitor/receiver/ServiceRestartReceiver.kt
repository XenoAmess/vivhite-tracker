package com.bilibili.livemonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager

class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LiveCheckService.ACTION_RESTART_SERVICE) {
            val preferenceManager = PreferenceManager(context)
            if (!preferenceManager.isServiceRunning()) {
                Log.d(TAG, "Service not supposed to run, skip restart")
                return
            }
            Log.d(TAG, "Restarting service...")
            try {
                val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                    putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service", e)
                // 如果直接启动失败，退而求其次用AlarmManager稍后重试
                AlarmReceiver().onReceive(context, Intent())
            }
        }
    }

    companion object {
        private const val TAG = "ServiceRestartReceiver"
    }
}
