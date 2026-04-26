package com.bilibili.livemonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferenceManager = PreferenceManager(context)
            if (preferenceManager.isServiceRunning()) {
                Log.d(TAG, "Boot completed, restarting service...")
                try {
                    val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                        putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
