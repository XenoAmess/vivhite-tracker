package com.bilibili.livemonitor.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.domain.SessionBackup
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 自动备份：每周把「场次 + 心情」混合 CSV 写到用户选的 SAF 目录。
 * 文件名 vivhite_backup_yyyyMMdd.csv（同日覆盖写由 DocumentsContract 去重命名处理）。
 * 未开开关/未选目录 → 直接成功跳过；写失败 → retry 一次（下次周期兜底）。
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
            val db = AppDatabase.get(applicationContext)
            val sessions = db.streamSessionDao().recentSessions(500)
            val moods = db.moodEventDao().all()
            val csv = SessionBackup.toCsv(sessions, moods)
            val name = "vivhite_backup_" +
                SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) + ".csv"
            writeToTree(treeUri, name, csv)
            prefs.setLastBackupTime(System.currentTimeMillis())
            AppLogger.d(TAG, "auto backup done: $name (${sessions.size} sessions, ${moods.size} moods)")
            Result.success()
        } catch (e: Exception) {
            AppLogger.w(TAG, "auto backup failed", e)
            Result.retry()
        }
    }

    // internal 便于单测（可注入假 tree uri 验证失败路径）；纯 framework API，无新依赖
    internal fun writeToTree(treeUri: String, fileName: String, content: String) {
        val resolver = applicationContext.contentResolver
        val tree = Uri.parse(treeUri)
        val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
        val fileUri = android.provider.DocumentsContract.createDocument(
            resolver, parentDoc, "text/csv", fileName
        ) ?: throw java.io.IOException("createDocument returned null")
        resolver.openOutputStream(fileUri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw java.io.IOException("openOutputStream returned null")
    }

    companion object {
        private const val TAG = "BackupWorker"
        private const val WORK_NAME = "auto_backup_periodic"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
