package com.bilibili.livemonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferenceManager = PreferenceManager(context)
            if (preferenceManager.isServiceRunning()) {
                val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                    putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
