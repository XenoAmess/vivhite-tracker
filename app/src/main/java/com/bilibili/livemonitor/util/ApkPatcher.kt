package com.bilibili.livemonitor.util

import android.content.Context
import io.sigpipe.jbsdiff.Patch
import java.io.File
import java.security.MessageDigest

// 增量补丁应用与 APK 校验。按补丁头自动分派：
// - "ZiPat1..." → ApkDiffPatch（libapkpatch.so，真机，压缩率远超 bsdiff）
// - "BSDIFF40" → jbsdiff（纯 JVM，兼容 version.json 里存量的 bsdiff 补丁）
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
     * 对 baseApk 应用补丁，输出到 outFile。按补丁头自动分派。
     *
     * 任何失败抛普通 Exception，由调用方（IncrementalUpdater）回退全量下载。
     * 注意：ApkDiffPatch 是 native，底包/补丁处理大，需在 IO 线程调用。
     */
    fun applyPatch(context: Context, baseApk: File, patchFile: File, outFile: File) {
        val magic = readMagic(patchFile)
        when {
            magic.startsWith(APKDIFF_PATCH_MAGIC) -> applyApkDiffPatch(context, baseApk, patchFile, outFile)
            magic.startsWith(BSDIFF_MAGIC) -> applyJbsdiff(baseApk, patchFile, outFile)
            else -> throw IllegalArgumentException("unknown patch format: ${magic.take(8)}")
        }
    }

    private fun applyApkDiffPatch(context: Context, baseApk: File, patchFile: File, outFile: File) {
        val tmp = File(context.cacheDir, "apkpatch_tmp.bin")
        try {
            val rc = com.github.sisong.ApkPatch.patch(
                baseApk.absolutePath,
                patchFile.absolutePath,
                outFile.absolutePath,
                MAX_UNCOMPRESS_MEMORY_BYTES,
                tmp.absolutePath,
                APKDIFF_THREAD_NUM
            )
            if (rc != 0) throw IllegalStateException("ApkDiffPatch apply failed rc=$rc")
            if (!outFile.exists() || outFile.length() <= 0) {
                throw IllegalStateException("ApkDiffPatch apply produced empty output")
            }
        } catch (e: Throwable) {
            // native 缺失/加载失败（UnsatisfiedLinkError）或打补丁失败统一转普通异常，
            // 供上层 catch(Exception) 回退全量下载，绝不让 Error 冒泡崩溃
            throw IllegalStateException(
                "ApkDiffPatch apply failed: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
        } finally {
            tmp.delete()
        }
    }

    private fun applyJbsdiff(baseApk: File, patchFile: File, outFile: File) {
        val oldBytes = baseApk.readBytes()
        val patchBytes = patchFile.readBytes()
        outFile.outputStream().buffered().use { out ->
            Patch.patch(oldBytes, patchBytes, out)
        }
    }

    private fun readMagic(file: File): String {
        return file.inputStream().buffered().use { input ->
            val buf = ByteArray(8)
            val read = input.read(buf)
            String(buf, 0, read.coerceAtLeast(0), Charsets.US_ASCII)
        }
    }

    private const val APKDIFF_PATCH_MAGIC = "ZiPat1"
    private const val BSDIFF_MAGIC = "BSDIFF40"
    // ApkDiffPatch 解压内存上限（128MB 覆盖 40MB 级 APK 的全量解压，超出走临时文件流式）
    private const val MAX_UNCOMPRESS_MEMORY_BYTES = 128L * 1024 * 1024
    private const val APKDIFF_THREAD_NUM = 2
}
