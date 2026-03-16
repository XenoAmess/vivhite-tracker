package com.bilibili.livemonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager

class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.bilibili.livemonitor.RESTART_SERVICE") {
            val preferenceManager = PreferenceManager(context)
            val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
