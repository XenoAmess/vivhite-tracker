package com.bilibili.livemonitor.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bilibili.livemonitor.api.BilibiliApi
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import java.util.concurrent.TimeUnit

class LiveCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val preferenceManager = PreferenceManager(applicationContext)
        if (!preferenceManager.isServiceRunning()) {
            AppLogger.d(TAG, "service not supposed to run, skip worker")
            return Result.success()
        }

        AppLogger.d(TAG, "worker triggered, service isRunning=${LiveCheckService.isRunning}")

        // 服务活着时由 Service 自己的 Alarm 驱动检测，Worker 只负责拉起死服务
        if (LiveCheckService.isRunning) {
            return Result.success()
        }

        AppLogger.w(TAG, "service dead, restarting via worker")
        return try {
            val serviceIntent = Intent(applicationContext, LiveCheckService::class.java).apply {
                putExtra(LiveCheckService.EXTRA_ROOM_ID, preferenceManager.getRoomId())
            }
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
            Result.success()
        } catch (e: Exception) {
            AppLogger.e(TAG, "worker restart service failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LiveCheckWorker"
        private const val PERIODIC_WORK_NAME = "live_check_periodic"
        private const val ONE_TIME_WORK_NAME = "live_check_one_time"

        fun schedulePeriodic(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<LiveCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                AppLogger.d(TAG, "periodic work scheduled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "schedule periodic work failed", e)
            }
        }

        fun scheduleOneTime(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<LiveCheckWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_TIME_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                AppLogger.d(TAG, "one-time work scheduled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "schedule one-time work failed", e)
            }
        }

        fun cancelAll(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
                AppLogger.d(TAG, "all work cancelled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "cancel work failed", e)
            }
        }
    }
}
