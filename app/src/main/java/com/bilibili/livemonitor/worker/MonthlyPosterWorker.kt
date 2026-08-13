package com.bilibili.livemonitor.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.StatsActivity
import com.bilibili.livemonitor.util.AnchorAvatarLoader
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.util.StatsImageDataFactory
import com.bilibili.livemonitor.util.StatsImageRenderer
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 月初自动生成上月绮迹手账海报：每日跑一次，进入新月份且上月有记录时
 * 渲染海报存 filesDir/posters/ 并发低打扰通知（点开手账页可再分享）。
 * 月份键去重（lastPosterMonth），同月重跑不再生成；空月跳过但也落键。
 */
class MonthlyPosterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferenceManager(applicationContext)
        val now = Calendar.getInstance()
        val currentKey = monthKey(now)
        if (prefs.getLastPosterMonth() == currentKey) return Result.success()

        return try {
            val lastMonth = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            val data = StatsImageDataFactory.build(applicationContext, lastMonth)
            if (data.records.isEmpty()) {
                // 空月跳过，但落键防每日重试
                prefs.setLastPosterMonth(currentKey)
                AppLogger.d(TAG, "empty month ${data.monthTitle}, skip poster")
                return Result.success()
            }
            val avatar = withTimeoutOrNull(4000) { AnchorAvatarLoader().load(applicationContext) }
            val bmp = StatsImageRenderer.render(applicationContext, data, avatar)
            val file = File(
                File(applicationContext.filesDir, "posters").apply { mkdirs() },
                "monthly_${currentKey}.png"
            )
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }

            notifyPoster(data.monthTitle)
            prefs.setLastPosterMonth(currentKey)
            AppLogger.d(TAG, "monthly poster generated: ${data.monthTitle}")
            Result.success()
        } catch (e: Exception) {
            AppLogger.w(TAG, "monthly poster failed", e)
            Result.retry()
        }
    }

    private fun notifyPoster(monthTitle: String) {
        val openIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, StatsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(
            applicationContext, LiveMonitorApp.CHANNEL_POSTER_ID
        )
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("上月绮迹手账已生成")
            .setContentText("$monthTitle 的海报准备好啦，点我看看")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        // 与 NotificationBuilder 同款：framework notify（lint MissingPermission 不挑这个）
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        nm.notify(LiveMonitorApp.NOTIFICATION_ID_POSTER, notification)
    }

    internal fun monthKey(cal: Calendar): String =
        "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

    companion object {
        private const val TAG = "MonthlyPosterWorker"
        private const val WORK_NAME = "monthly_poster_periodic"

        fun schedule(context: Context) {
            // Application.onCreate 调用：Robolectric 测试里 WorkManager 尚未初始化，
            // getInstance 会抛 "not initialized"——排程失败非致命，下次启动重试
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<MonthlyPosterWorker>(1, TimeUnit.DAYS).build()
                )
            }.onFailure { AppLogger.d(TAG, "schedule skipped: ${it.message}") }
        }
    }
}
