package com.bilibili.livemonitor.receiver

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.worker.LiveCheckWorker

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferenceManager = PreferenceManager(context)
            if (preferenceManager.isServiceRunning()) {
                AppLogger.d(TAG, "Boot completed, restarting service...")
                try {
                    val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                        putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                        AppLogger.e(TAG, "FGS start not allowed on boot, fallback to WorkManager", e)
                    } else {
                        AppLogger.e(TAG, "Failed to start service on boot, fallback to WorkManager", e)
                    }
                    LiveCheckWorker.scheduleOneTime(context)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
