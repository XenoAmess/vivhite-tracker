package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.domain.UpdateDecider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

open class UpdateChecker {

    // 检查 GitHub 最新 Release：优先 version.json 精确版本号，回退 APK 文件名解析
    suspend fun checkLatestRelease(localVersionCode: Int): UpdateDecider.UpdateState =
        withContext(Dispatchers.IO) {
            val releaseJson = httpGet(LATEST_RELEASE_API)
                ?: return@withContext UpdateDecider.UpdateState.Error("network error")
            val raw = UpdateDecider.parseLatestRelease(releaseJson)
                ?: return@withContext UpdateDecider.UpdateState.Error("release parse error")
            val remoteVersion = raw.versionJsonUrl
                ?.let { httpGet(it) }
                ?.let { UpdateDecider.parseVersionJson(it) }
                ?: raw.apkFileName?.let { UpdateDecider.parseApkFileName(it) }
            UpdateDecider.decide(localVersionCode, remoteVersion, raw)
        }

    // internal open：测试可注入 fake 响应
    internal open suspend fun httpGet(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpsURLConnection
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

    // 下载 APK 到 dest，onProgress 回调 0-100（在 IO 线程调用，UI 更新需自行切线程）；
    // 成功 true，失败清理半成品文件返回 false
    open suspend fun downloadApk(
        url: String,
        dest: File,
        onProgress: (percent: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpsURLConnection
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
    }
}
