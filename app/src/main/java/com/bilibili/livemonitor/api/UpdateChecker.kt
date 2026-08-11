package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.domain.UpdateDecider
import com.bilibili.livemonitor.domain.UpdateMirrors
import com.bilibili.livemonitor.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

open class UpdateChecker {

    // internal open var：测试可指向本地 HttpServer 做真实端到端；
    // 生产恒为 LATEST_RELEASE_API
    internal open var latestReleaseApi: String = LATEST_RELEASE_API

    // internal open var：内测版尝鲜通道（GitHub Pages 上的 master 最新构建），
    // 测试可指向本地 HttpServer
    internal open var betaVersionJsonUrl: String = BETA_VERSION_JSON_URL

    // 检查 GitHub 最新 Release：version.json 提供精确 versionCode，缺失则返回 Error
    suspend fun checkLatestRelease(
        localVersionCode: Int,
        localVersionName: String
    ): UpdateDecider.UpdateState =
        withContext(Dispatchers.IO) {
            val releaseJson = httpGet(latestReleaseApi)
                ?: return@withContext UpdateDecider.UpdateState.Error("network error")
            val raw = UpdateDecider.parseLatestRelease(releaseJson)
                ?: return@withContext UpdateDecider.UpdateState.Error("release parse error")
            val versionJsonText = raw.versionJsonUrl?.let { httpGet(it) }
            val meta = versionJsonText?.let { UpdateDecider.parseVersionMeta(it) }
            val remoteVersion = meta?.let { it.versionCode to it.versionName }
            val state = UpdateDecider.decide(localVersionCode, localVersionName, remoteVersion, raw)
            if (state is UpdateDecider.UpdateState.UpdateAvailable && meta != null) {
                // version.json 里的提交摘要优先于 release body（两通道更新说明统一来源）；
                // 附带增量更新元数据（当前 versionCode 的升级链，无则 null → 全量）
                state.info.copy(
                    changelog = meta.changelog ?: state.info.changelog,
                    apkSha256 = meta.apkSha256,
                    apkSize = meta.apkSize,
                    chain = meta.chains[localVersionCode]
                ).let { return@withContext UpdateDecider.UpdateState.UpdateAvailable(it) }
            }
            state
        }

    // 检查内测版尝鲜通道（GitHub Pages 上的 master 最新构建）：
    // 只需 version.json，versionCode 比较天然防降级（本地比频道新 → UpToDate）
    suspend fun checkBetaChannel(
        localVersionCode: Int,
        localVersionName: String
    ): UpdateDecider.UpdateState =
        withContext(Dispatchers.IO) {
            val versionJsonText = httpGet(betaVersionJsonUrl)
                ?: return@withContext UpdateDecider.UpdateState.Error("network error")
            val meta = UpdateDecider.parseVersionMeta(versionJsonText)
                ?: return@withContext UpdateDecider.UpdateState.Error("release parse error")
            val raw = UpdateDecider.RawRelease(
                tagName = BETA_TAG_NAME,
                changelog = meta.changelog ?: "主分支最新内测构建",
                apkUrl = BETA_APK_URL,
                apkFileName = BETA_APK_NAME,
                versionJsonUrl = betaVersionJsonUrl
            )
            val state = UpdateDecider.decide(
                localVersionCode, localVersionName, meta.versionCode to meta.versionName, raw
            )
            if (state is UpdateDecider.UpdateState.UpdateAvailable) {
                state.info.copy(
                    apkSha256 = meta.apkSha256,
                    apkSize = meta.apkSize,
                    chain = meta.chains[localVersionCode]
                ).let { return@withContext UpdateDecider.UpdateState.UpdateAvailable(it) }
            }
            state
        }

    // internal open：测试可注入 fake 响应
    internal open suspend fun httpGet(url: String): String? {
        return try {
            // 用父类 HttpURLConnection 接收：生产 https 行为不变，
            // 测试可用本地 http HttpServer 做真实端到端
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            body
        } catch (e: Exception) {
            null
        }
    }

    // 下载 APK 到 dest，onProgress 回调 0-100（在 IO 线程调用，UI 更新需自行切线程）。
    // github.com 资产按 UpdateMirrors.candidates 顺序走公共镜像加速，全部失败回退直连；
    // 成功 true，失败清理半成品文件返回 false
    open suspend fun downloadApk(
        url: String,
        dest: File,
        onProgress: (percent: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = UpdateMirrors.candidates(url)
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) {
                AppLogger.d("UpdateChecker", "download fallback: $candidate")
                onProgress(0) // 重试时进度条归零
            }
            if (downloadOnce(candidate, dest, onProgress)) return@withContext true
        }
        dest.delete()
        false
    }

    private fun downloadOnce(
        url: String,
        dest: File,
        onProgress: (percent: Int) -> Unit
    ): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 10000
                readTimeout = 15000
            }
            val total = connection.contentLength.toLong()
            dest.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            connection.disconnect()
            true
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }

    companion object {
        private const val USER_AGENT = "vivhite-tracker-updater"
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/XenoAmess/vivhite-tracker/releases/latest"

        // 内测版尝鲜通道：GitHub Pages 上的 master 最新构建（android-ci.yml 部署）
        const val BETA_VERSION_JSON_URL =
            "https://xenoamess.github.io/vivhite-tracker/beta/version.json"
        const val BETA_APK_URL =
            "https://xenoamess.github.io/vivhite-tracker/beta/vivhite-tracker-beta.apk"
        const val BETA_TAG_NAME = "beta"
        const val BETA_APK_NAME = "vivhite-tracker-beta.apk"
    }
}
