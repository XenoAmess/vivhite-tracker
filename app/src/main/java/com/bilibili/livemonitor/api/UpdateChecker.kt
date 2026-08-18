package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.domain.UpdateDecider
import com.bilibili.livemonitor.domain.UpdateMirrors
import com.bilibili.livemonitor.util.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

open class UpdateChecker {

    // internal open var：测试可指向本地 HttpServer 做真实端到端；
    // 生产恒为 LATEST_RELEASE_API
    internal open var latestReleaseApi: String = LATEST_RELEASE_API

    // internal open var：内测版尝鲜通道（beta-archive 滚动 release 的固定资产 URL，
    // github.com 主机 → UpdateMirrors 镜像加速天然生效），测试可指向本地 HttpServer
    internal open var betaVersionJsonUrl: String = BETA_VERSION_JSON_URL

    // internal open var：stable 检查的 302 免 API 回退入口（releases/latest 页），
    // 测试可指向本地 HttpServer
    internal open var latestReleasePageUrl: String = LATEST_RELEASE_PAGE_URL

    // internal open var：beta 主通道失败时的 legacy 回退（GitHub Pages，不可代理直连），
    // 测试可指向本地 HttpServer
    internal open var betaLegacyVersionJsonUrl: String = BETA_VERSION_JSON_URL_LEGACY

    // 检查 GitHub 最新 Release：version.json 提供精确 versionCode，缺失则返回 Error；
    // api.github.com 失败时回退 releases/latest 302 路径（全程可代理 URL）
    suspend fun checkLatestRelease(
        localVersionCode: Int,
        localVersionName: String
    ): UpdateDecider.UpdateState =
        withContext(Dispatchers.IO) {
            val releaseJson = httpGetWithMirrors(latestReleaseApi)
            if (releaseJson == null) {
                return@withContext checkLatestViaRedirect(localVersionCode, localVersionName)
            }
            val raw = UpdateDecider.parseLatestRelease(releaseJson)
                ?: return@withContext UpdateDecider.UpdateState.Error("release parse error")
            val versionJsonText = raw.versionJsonUrl?.let { httpGetWithMirrors(it) }
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

    /**
     * 302 免 API 检查路径：GET releases/latest 不跟随重定向，从 Location 提取最新 tag，
     * 再拼 releases/download/<tag>/version.json 拉精确版本。
     * api.github.com 不可代理而这条路径全程 github.com 资产 URL（UpdateMirrors 可加速）。
     */
    private suspend fun checkLatestViaRedirect(
        localVersionCode: Int,
        localVersionName: String
    ): UpdateDecider.UpdateState {
        val resp = httpGetRawWithMirrors(latestReleasePageUrl, followRedirects = false)
            ?: return UpdateDecider.UpdateState.Error("network error")
        // 正常：302 + Location 头；镜像可能代跟重定向返回 200 HTML → 从页面正则提取 tag
        val tagUrl = resp.location?.let { URL(URL(latestReleasePageUrl), it).toString() }
            ?: resp.body?.let { body ->
                RELEASE_TAG_REGEX.find(body)?.let { match ->
                    URL(URL(latestReleasePageUrl), match.value).toString()
                }
            }
            ?: return UpdateDecider.UpdateState.Error("release page no tag (code=${resp.code})")
        val tag = tagUrl.substringAfterLast("/releases/tag/", "")
        if (tag.isBlank()) {
            return UpdateDecider.UpdateState.Error("release tag parse error")
        }
        val downloadBase = tagUrl.substringBefore("/releases/tag/")
        val versionJsonUrl = "$downloadBase/releases/download/$tag/version.json"
        val metaText = httpGetWithMirrors(versionJsonUrl)
            ?: return UpdateDecider.UpdateState.Error("network error")
        val meta = UpdateDecider.parseVersionMeta(metaText)
            ?: return UpdateDecider.UpdateState.Error("release parse error")
        val apkName = "vivhite-tracker-${meta.versionName}.apk"
        val raw = UpdateDecider.RawRelease(
            tagName = tag,
            changelog = meta.changelog ?: "",
            apkUrl = "$downloadBase/releases/download/$tag/$apkName",
            apkFileName = apkName,
            versionJsonUrl = versionJsonUrl
        )
        val state = UpdateDecider.decide(
            localVersionCode, localVersionName, meta.versionCode to meta.versionName, raw
        )
        if (state is UpdateDecider.UpdateState.UpdateAvailable) {
            state.info.copy(
                apkSha256 = meta.apkSha256,
                apkSize = meta.apkSize,
                chain = meta.chains[localVersionCode]
            ).let { return UpdateDecider.UpdateState.UpdateAvailable(it) }
        }
        return state
    }

    // 检查内测版尝鲜通道（beta-archive 滚动 release 的固定资产 URL）：
    // 只需 version.json，versionCode 比较天然防降级（本地比频道新 → UpToDate）。
    // 主 URL 失败回退 legacy Pages 通道（过渡期兼容，不可代理直连）
    suspend fun checkBetaChannel(
        localVersionCode: Int,
        localVersionName: String
    ): UpdateDecider.UpdateState =
        withContext(Dispatchers.IO) {
            val primaryText = httpGetWithMirrors(betaVersionJsonUrl)
            val (versionJsonText, apkUrl, versionJsonUrl) = if (primaryText != null) {
                Triple(primaryText, BETA_APK_URL, betaVersionJsonUrl)
            } else {
                val legacyText = httpGet(betaLegacyVersionJsonUrl)
                    ?: return@withContext UpdateDecider.UpdateState.Error("network error")
                Triple(legacyText, BETA_APK_URL_LEGACY, betaLegacyVersionJsonUrl)
            }
            val meta = UpdateDecider.parseVersionMeta(versionJsonText)
                ?: return@withContext UpdateDecider.UpdateState.Error("release parse error")
            val raw = UpdateDecider.RawRelease(
                tagName = BETA_TAG_NAME,
                changelog = meta.changelog ?: "主分支最新内测构建",
                apkUrl = apkUrl,
                apkFileName = BETA_APK_NAME,
                versionJsonUrl = versionJsonUrl
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

    // 镜像轮询 GET：UpdateMirrors.candidates 逐个尝试（白名单外 URL 单候选直连，行为不变）
    internal suspend fun httpGetWithMirrors(url: String): String? {
        val candidates = UpdateMirrors.candidates(url)
        candidates.forEachIndexed { index, candidate ->
            val body = httpGet(candidate)
            if (body != null) return body
        }
        return null
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

    /** 原始 GET（可选不跟随重定向）：状态码 + Location + body，302 检查路径用 */
    internal data class GetResult(val code: Int, val location: String?, val body: String?)

    private suspend fun httpGetRawWithMirrors(url: String, followRedirects: Boolean): GetResult? {
        UpdateMirrors.candidates(url).forEachIndexed { index, candidate ->
            val result = httpGetRaw(candidate, followRedirects)
            if (result != null) return result
        }
        return null
    }

    // internal open：测试可注入 fake 响应
    internal open suspend fun httpGetRaw(url: String, followRedirects: Boolean): GetResult? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                instanceFollowRedirects = followRedirects
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = runCatching {
                connection.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
            val result = GetResult(
                connection.responseCode,
                connection.getHeaderField("Location"),
                body
            )
            connection.disconnect()
            result
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
        var partial: File? = null
        var publishedByThisCall = false
        val candidates = UpdateMirrors.candidates(url)
        try {
            candidates.forEachIndexed { index, candidate ->
                currentCoroutineContext().ensureActive()
                if (index > 0) {
                    onProgress(0) // 重试时进度条归零
                }
                partial = AppUpdater.tempFileFor(dest)
                if (downloadOnce(candidate, partial!!, onProgress)) {
                    currentCoroutineContext().ensureActive()
                }
                if (partial?.isFile == true && AppUpdater.publishAtomically(partial!!, dest)) {
                    publishedByThisCall = true
                    partial = null
                    currentCoroutineContext().ensureActive()
                    return@withContext true
                }
                partial?.delete()
                partial = null
            }
            dest.delete()
            false
        } finally {
            partial?.delete()
            if (!currentCoroutineContext().isActive && publishedByThisCall) dest.delete()
        }
    }

    private suspend fun downloadOnce(
        url: String,
        dest: File,
        onProgress: (percent: Int) -> Unit
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation {
            connection.getAndSet(null)?.disconnect()
            dest.delete()
        }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        try {
            val openedConnection = URL(url).openConnection() as HttpURLConnection
            connection.set(openedConnection)
            if (!continuation.isActive) {
                openedConnection.disconnect()
                return@suspendCancellableCoroutine
            }
            openedConnection.apply {
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 10000
                readTimeout = 15000
            }
            val total = openedConnection.contentLength.toLong()
            dest.parentFile?.mkdirs()
            openedConnection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (continuation.isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (continuation.isActive) continuation.resume(true)
        } catch (_: Exception) {
            dest.delete()
            if (continuation.isActive) continuation.resume(false)
        } finally {
            connection.getAndSet(null)?.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT = "vivhite-tracker-updater"

        // 镜像代跟重定向时从 releases tag 页 HTML 提取 tag 用
        private val RELEASE_TAG_REGEX = Regex("""/releases/tag/([^/"?#\s]+)""")

        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/XenoAmess/vivhite-tracker/releases/latest"

        // stable 检查的 302 免 API 回退入口（github.com 主机，可镜像加速）
        const val LATEST_RELEASE_PAGE_URL =
            "https://github.com/XenoAmess/vivhite-tracker/releases/latest"

        // 内测版尝鲜通道：beta-archive 滚动 release 的固定资产 URL
        // （github.com 主机，UpdateMirrors 镜像加速生效；CI build_beta_chains.py 维护）
        const val BETA_VERSION_JSON_URL =
            "https://github.com/XenoAmess/vivhite-tracker/releases/download/beta-archive/version.json"
        const val BETA_APK_URL =
            "https://github.com/XenoAmess/vivhite-tracker/releases/download/beta-archive/beta-latest.apk"

        // legacy：GitHub Pages 通道（不可代理，仅作主通道失败的回退）
        const val BETA_VERSION_JSON_URL_LEGACY =
            "https://xenoamess.github.io/vivhite-tracker/beta/version.json"
        const val BETA_APK_URL_LEGACY =
            "https://xenoamess.github.io/vivhite-tracker/beta/vivhite-tracker-beta.apk"

        const val BETA_TAG_NAME = "beta"
        const val BETA_APK_NAME = "vivhite-tracker-beta.apk"
    }
}
