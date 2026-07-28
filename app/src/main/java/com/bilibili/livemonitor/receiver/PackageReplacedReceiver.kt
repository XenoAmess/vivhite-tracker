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

// 覆盖安装（含应用内更新）后系统会杀掉进程，FGS 随之消失。
// 正常路径下 60s 闹钟会拉起服务，但部分 ROM 升级时清闹钟，
// 此 receiver 把那种场景的空窗从 15min（周期 Worker 兜底）压缩到秒级。
class PackageReplacedReceiver : BroadcastReceiver() {

    // internal var：测试可注入抛异常的 fake starter，验证 WorkManager 降级路径
    internal var starter: ServiceStarter = DefaultServiceStarter()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferenceManager = PreferenceManager(context)
        // prefs 是监控开关的唯一权威：用户已停止时升级绝不能复活监控
        if (!preferenceManager.isServiceRunning()) {
            AppLogger.d(TAG, "package replaced but service not supposed to run, skip")
            return
        }
        AppLogger.d(TAG, "package replaced, restarting monitor service")
        try {
            val serviceIntent = Intent(context, LiveCheckService::class.java).apply {
                putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
            }
            starter.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                AppLogger.e(TAG, "FGS start not allowed after upgrade, fallback to WorkManager", e)
            } else {
                AppLogger.e(TAG, "Failed to restart service after upgrade, fallback to WorkManager", e)
            }
            // 后台启动FGS被拒（Android 12+），降级为WorkManager一次性任务拉起
            LiveCheckWorker.scheduleOneTime(context)
        }
    }

    companion object {
        private const val TAG = "PackageReplacedReceiver"
    }
}
