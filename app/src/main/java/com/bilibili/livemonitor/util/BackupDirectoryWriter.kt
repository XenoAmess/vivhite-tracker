package com.bilibili.livemonitor.util

import android.content.Context
import android.provider.DocumentsContract
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SAF 备份目录读写：自动（BackupWorker）与手动（设置页「立即备份」）共用。
 * 文件名带秒级时间戳，同日多份不覆盖；写入成功后按 30 天 + 60 份上限清理旧备份。
 */
object BackupDirectoryWriter {

    private const val NAME_PREFIX = "vivhite_backup_"
    private const val NAME_SUFFIX = ".zip"
    private const val KEEP_DAYS = 30L
    private const val MAX_KEEP_COUNT = 60
    private const val DAY_MS = 86_400_000L
    private const val TAG = "BackupDirectoryWriter"

    fun backupFileName(now: Long): String =
        NAME_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(now)) +
            NAME_SUFFIX

    /** 写备份到 SAF 目录并清理旧备份，返回文件名。清理失败只记日志不影响备份结果。 */
    suspend fun write(
        context: Context,
        treeUri: String,
        writer: suspend (java.io.OutputStream) -> Unit
    ): String {
        val name = backupFileName(System.currentTimeMillis())
        writeToTree(context, treeUri, name, writer)
        pruneOldBackups(context, treeUri, System.currentTimeMillis())
        return name
    }

    /** 目录内现有备份文件名（状态行「共 N 份」与清理共用）。失败返回 null。 */
    fun listBackupNames(context: Context, treeUri: String): List<String>? {
        val tree = android.net.Uri.parse(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        return runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }?.filter(::isBackupFile)
        }.onFailure { AppLogger.w(TAG, "list backups failed", it) }.getOrNull()
    }

    /** 纯函数：给定备份文件名列表，返回应删除的名字（30 天前 + 超 60 份取最旧）。 */
    internal fun namesToDelete(names: List<String>, now: Long): List<String> {
        val parsed = names.mapNotNull { name -> backupTimeMillis(name)?.let { name to it } }
        val cutoff = now - KEEP_DAYS * DAY_MS
        val tooOld = parsed.filter { it.second < cutoff }
        val remaining = parsed - tooOld.toSet()
        val excess = if (remaining.size > MAX_KEEP_COUNT) {
            remaining.sortedBy { it.second }.take(remaining.size - MAX_KEEP_COUNT)
        } else {
            emptyList<Pair<String, Long>>()
        }
        return (tooOld + excess).map { it.first }
    }

    internal fun isBackupFile(name: String): Boolean = backupTimeMillis(name) != null

    /** 兼容旧格式 vivhite_backup_yyyyMMdd.zip（按当天 0 点计）。 */
    internal fun backupTimeMillis(name: String): Long? {
        val match = Regex("^vivhite_backup_(\\d{8})(?:_(\\d{6}))?\\.zip$").matchEntire(name)
            ?: return null
        return runCatching {
            val pattern = if (match.groupValues[2].isEmpty()) "yyyyMMdd" else "yyyyMMdd_HHmmss"
            SimpleDateFormat(pattern, Locale.getDefault()).parse(
                name.removePrefix(NAME_PREFIX).removeSuffix(NAME_SUFFIX)
            )?.time
        }.getOrNull()
    }

    private fun pruneOldBackups(context: Context, treeUri: String, now: Long) {
        runCatching {
            val tree = android.net.Uri.parse(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val entries = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                ),
                null, null, null
            )?.use { cursor ->
                buildList<Pair<String, String>> {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            } ?: return
            val toDelete = namesToDelete(entries.map { it.first }, now).toSet()
            entries.filter { it.first in toDelete }.forEach { (_, docId) ->
                runCatching {
                    DocumentsContract.deleteDocument(
                        context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    )
                }
            }
            if (toDelete.isNotEmpty()) AppLogger.d(TAG, "pruned ${toDelete.size} old backups")
        }.onFailure { AppLogger.w(TAG, "prune backups failed", it) }
    }

    private suspend fun writeToTree(
        context: Context,
        treeUri: String,
        fileName: String,
        writer: suspend (java.io.OutputStream) -> Unit
    ) {
        val resolver = context.contentResolver
        val tree = android.net.Uri.parse(treeUri)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val fileUri = DocumentsContract.createDocument(
            resolver, parentDoc, "application/zip", fileName
        ) ?: throw IOException("createDocument returned null")
        try {
            resolver.openOutputStream(fileUri, "wt")?.use {
                writer(it)
            } ?: throw IOException("openOutputStream returned null")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
            throw e
        }
    }
}
