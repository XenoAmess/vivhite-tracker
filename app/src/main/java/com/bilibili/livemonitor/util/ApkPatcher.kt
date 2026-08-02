package com.bilibili.livemonitor.util

import android.content.Context
import io.sigpipe.jbsdiff.Patch
import java.io.File
import java.security.MessageDigest

// bsdiff 打补丁与 APK 校验。底层 jbsdiff（bsdiff 的 Java 移植），纯 JVM。
object ApkPatcher {

    // 已安装 APK 的文件路径（系统保留安装时的原始 APK，可读取用于增量打底）
    fun installedApkFile(context: Context): File? {
        return try {
            val sourceDir = context.applicationInfo.sourceDir ?: return null
            File(sourceDir).takeIf { it.exists() && it.canRead() }
        } catch (e: Exception) {
            null
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 对 baseApk 应用 bsdiff 补丁，输出到 outFile。
     * 注意：jbsdiff 把底包整个读入内存（~41MB），主线程调用会卡 UI，
     * 调用方需在 IO 线程执行。
     */
    fun applyPatch(baseApk: File, patchFile: File, outFile: File) {
        val oldBytes = baseApk.readBytes()
        val patchBytes = patchFile.readBytes()
        outFile.outputStream().buffered().use { out ->
            Patch.patch(oldBytes, patchBytes, out)
        }
    }
}
