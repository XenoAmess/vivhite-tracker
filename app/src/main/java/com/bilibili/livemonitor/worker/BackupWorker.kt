package com.bilibili.livemonitor.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import java.util.concurrent.TimeUnit

/**
 * 自动备份：每天把全量数据（场次+心情+主题变化+人气点+粉丝快照+
 * 魔法期与设置快照+头像/封面原图+月报海报+运行日志）打成 ZIP 写到用户选的 SAF 目录。
 * 文件名 vivhite_backup_yyyyMMdd_HHmmss.zip；保留 30 天且最多 60 份
 * （写入/清理逻辑在 util/BackupDirectoryWriter，与设置页手动备份共用）。
 * 未开开关/未选目录 → 直接成功跳过；写失败 → retry（明天兜底）。
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferenceManager(applicationContext)
        val treeUri = prefs.getBackupTreeUri()
        if (!prefs.isAutoBackupEnabled() || treeUri.isBlank()) {
            AppLogger.d(TAG, "auto backup disabled or no dir, skip")
            return Result.success()
        }
        return try {
            val name = com.bilibili.livemonitor.util.BackupDirectoryWriter.write(
                applicationContext, treeUri
            ) { output ->
                com.bilibili.livemonitor.util.FullBackupBuilder.write(applicationContext, output)
            }
            prefs.setLastBackupTime(System.currentTimeMillis())
            AppLogger.d(TAG, "auto backup done: $name")
            Result.success()
        } catch (e: Exception) {
            AppLogger.w(TAG, "auto backup failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BackupWorker"
        private const val WORK_NAME = "auto_backup_periodic"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
