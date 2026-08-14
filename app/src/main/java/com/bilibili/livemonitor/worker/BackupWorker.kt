package com.bilibili.livemonitor.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 自动备份：每天把全量数据（场次+心情+主题变化+人气点+粉丝快照+
 * 魔法期与设置快照+封面原图）打成 ZIP 写到用户选的 SAF 目录。
 * 文件名 vivhite_backup_yyyyMMdd.zip。未开开关/未选目录 → 直接成功跳过；
 * 写失败 → retry（明天兜底）。
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
            val zipBytes = com.bilibili.livemonitor.util.FullBackupBuilder
                .build(applicationContext)
            val name = "vivhite_backup_" +
                SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) + ".zip"
            writeToTree(treeUri, name, zipBytes)
            prefs.setLastBackupTime(System.currentTimeMillis())
            AppLogger.d(TAG, "auto backup done: $name (${zipBytes.size} bytes)")
            Result.success()
        } catch (e: Exception) {
            AppLogger.w(TAG, "auto backup failed", e)
            Result.retry()
        }
    }

    // internal 便于单测（可注入假 tree uri 验证失败路径）；纯 framework API，无新依赖
    internal fun writeToTree(treeUri: String, fileName: String, content: ByteArray) {
        val resolver = applicationContext.contentResolver
        val tree = Uri.parse(treeUri)
        val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
        val fileUri = android.provider.DocumentsContract.createDocument(
            resolver, parentDoc, "application/zip", fileName
        ) ?: throw java.io.IOException("createDocument returned null")
        resolver.openOutputStream(fileUri, "wt")?.use {
            it.write(content)
        } ?: throw java.io.IOException("openOutputStream returned null")
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
