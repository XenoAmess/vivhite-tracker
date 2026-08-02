package com.bilibili.livemonitor.util

import android.content.Context
import com.bilibili.livemonitor.domain.UpdateDecider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 增量更新编排：按链逐跳 下载补丁 → 校验 patchSha256 → 打补丁 → 校验 resultSha256。
 * 全部成功返回最终 APK 文件；任一步失败返回 null（调用方回退全量下载）。
 *
 * downloader/patcher 可注入：单测用内存 fake 驱动全链路，不依赖网络/真 APK。
 */
class IncrementalUpdater(
    private val context: Context,
    // internal 注入位：测试替换为内存下载器
    internal var downloader: suspend (url: String, dest: File, onProgress: (Int) -> Unit) -> Boolean =
        { url, dest, onProgress -> com.bilibili.livemonitor.api.UpdateChecker().downloadApk(url, dest, onProgress) },
    internal var patcher: (base: File, patch: File, out: File) -> Unit =
        { base, patch, out -> ApkPatcher.applyPatch(base, patch, out) },
    // internal 注入位：测试指向 fixture 底包（Robolectric 改不动 context.applicationInfo）
    internal var installedApkProvider: (Context) -> File? = { ApkPatcher.installedApkFile(it) }
) {

    /**
     * @param chain 升级链（hops 至少一跳）
     * @param finalVersionName 最终 APK 命名用（与全量下载路径同目录同规则）
     * @param onProgress 0-100 总进度（按各跳 size 加权）
     */
    suspend fun executeChain(
        chain: UpdateDecider.UpdateChain,
        finalVersionName: String,
        onProgress: (percent: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val workDir = File(context.filesDir, "updates/incremental")
        workDir.deleteRecursively()
        workDir.mkdirs()

        val base = installedApkProvider(context)
        if (base == null) {
            AppLogger.w(TAG, "incremental: installed apk unreadable")
            return@withContext null
        }
        val baseFile: File = base
        val baseSha = ApkPatcher.sha256(baseFile)
        if (!baseSha.equals(chain.fromApkSha256, ignoreCase = true)) {
            AppLogger.w(TAG, "incremental: base apk sha mismatch (local=$baseSha expect=${chain.fromApkSha256})")
            return@withContext null
        }

        var current = baseFile
        var downloadedBefore = 0L
        val totalSize = chain.totalSize.takeIf { it > 0 } ?: chain.hops.sumOf { it.size }

        for ((index, hop) in chain.hops.withIndex()) {
            val patchFile = File(workDir, "hop-$index.bspatch")
            val hopBase = downloadedBefore
            val ok = downloader(hop.url, patchFile) { hopPercent ->
                val overall = if (totalSize > 0) {
                    ((hopBase + hop.size * hopPercent / 100) * 100 / totalSize).toInt()
                } else hopPercent
                onProgress(overall.coerceIn(0, 100))
            }
            if (!ok) {
                AppLogger.w(TAG, "incremental: hop $index download failed")
                cleanup(workDir); return@withContext null
            }
            val patchSha = ApkPatcher.sha256(patchFile)
            if (!patchSha.equals(hop.patchSha256, ignoreCase = true)) {
                AppLogger.w(TAG, "incremental: hop $index patch sha mismatch")
                cleanup(workDir); return@withContext null
            }

            val isLast = index == chain.hops.lastIndex
            val outFile = if (isLast) {
                AppUpdater.apkFile(context, finalVersionName)
            } else {
                File(workDir, "hop-$index.apk")
            }
            try {
                patcher(current, patchFile, outFile)
            } catch (e: Exception) {
                AppLogger.e(TAG, "incremental: hop $index patch apply failed", e)
                outFile.delete(); cleanup(workDir); return@withContext null
            }
            val resultSha = ApkPatcher.sha256(outFile)
            if (!resultSha.equals(hop.resultSha256, ignoreCase = true)) {
                AppLogger.w(TAG, "incremental: hop $index result sha mismatch")
                outFile.delete(); cleanup(workDir); return@withContext null
            }
            AppLogger.d(TAG, "incremental: hop $index -> vc ${hop.toVersionCode} ok (${hop.size} bytes)")
            downloadedBefore += hop.size
            patchFile.delete()
            current = outFile
        }

        cleanup(workDir)
        AppLogger.d(TAG, "incremental: chain complete, ${chain.hops.size} hop(s), saved vs full apk")
        current
    }

    private fun cleanup(workDir: File) {
        workDir.deleteRecursively()
    }

    companion object {
        private const val TAG = "IncrementalUpdater"
    }
}
