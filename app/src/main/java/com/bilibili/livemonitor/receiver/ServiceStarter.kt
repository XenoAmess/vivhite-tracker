package com.bilibili.livemonitor.receiver

import android.content.Context
import android.content.Intent

/**
 * 启动前台服务的包装接口，便于测试注入抛异常的 fake，
 * 验证 FGS 启动被拒（Android 12+ 后台限制）时的 WorkManager 降级路径。
 */
interface ServiceStarter {
    fun startForegroundService(context: Context, intent: Intent)
}

class DefaultServiceStarter : ServiceStarter {
    override fun startForegroundService(context: Context, intent: Intent) {
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}
