package com.bilibili.livemonitor.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// 更新包落地路径、安装 intent、安装权限与网络环境探测
object AppUpdater {

    fun apkFile(context: Context, versionName: String): File =
        File(context.filesDir, "updates/vivhite-tracker-$versionName.apk")

    internal fun tempFileFor(destination: File): File {
        destination.parentFile?.mkdirs()
        return File.createTempFile(".${destination.name}.", ".part", destination.parentFile)
    }

    internal fun publishAtomically(tempFile: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        return try {
            Files.move(
                tempFile.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            runCatching {
                Files.move(
                    tempFile.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /** Removes updater-owned stale packages, partial files, and work directories only. */
    fun cleanupOldDownloads(
        context: Context,
        keepApk: File? = null,
        apkMaxAgeMs: Long = OLD_APK_MAX_AGE_MS,
        now: Long = System.currentTimeMillis()
    ) {
        val updatesDir = File(context.filesDir, "updates")
        val canonicalRoot = runCatching { updatesDir.canonicalFile }.getOrNull() ?: return
        val canonicalKeep = keepApk?.let { runCatching { it.canonicalFile }.getOrNull() }
        updatesDir.listFiles()?.forEach { entry ->
            val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return@forEach
            if (canonicalEntry.parentFile != canonicalRoot || canonicalEntry == canonicalKeep) {
                return@forEach
            }
            when {
                canonicalEntry.isDirectory -> canonicalEntry.deleteRecursively()
                canonicalEntry.name.endsWith(".part") -> canonicalEntry.delete()
                canonicalEntry.name.endsWith(".apk") &&
                    now - canonicalEntry.lastModified() >= apkMaxAgeMs ->
                    canonicalEntry.delete()
            }
        }
    }

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    internal const val OLD_APK_MAX_AGE_MS = 7L * 24 * 3600 * 1000
}
