package com.bilibili.livemonitor.receiver

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.worker.LiveCheckWorker

class ServiceRestartReceiver : BroadcastReceiver() {

    // internal var：测试可注入抛异常的 fake starter，验证 WorkManager 降级路径
    internal var starter: ServiceStarter = DefaultServiceStarter()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LiveCheckService.ACTION_RESTART_SERVICE) {
            val preferenceManager = PreferenceManager(context)
            if (!preferenceManager.isServiceRunning()) {
                AppLogger.d(TAG, "Service not supposed to run, skip restart")
                return
            }
            AppLogger.d(TAG, "Restarting service...")
            try {
                val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                    putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
                }
                starter.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    AppLogger.e(TAG, "FGS start not allowed on restart, fallback to WorkManager", e)
                } else {
                    AppLogger.e(TAG, "Failed to restart service, fallback to WorkManager", e)
                }
                // 后台启动FGS被拒，降级为WorkManager一次性任务拉起
                LiveCheckWorker.scheduleOneTime(context)
            }
        }
    }

    companion object {
        private const val TAG = "ServiceRestartReceiver"
    }
}
