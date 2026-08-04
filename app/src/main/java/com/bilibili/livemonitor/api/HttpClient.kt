package com.bilibili.livemonitor.api

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 共享 HTTP GET 客户端。无第三方依赖，原生 HttpsURLConnection。
 * BilibiliApi / BilibiliActivityApi / ShareImageLoader 统一走这里，UA/超时/Referer 单一来源。
 */
object HttpClient {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 默认 Referer：B 站通用 */
    private const val DEFAULT_REFERER = "https://www.bilibili.com/"

    /**
     * 返回配置好的连接（调用方负责 inputStream 读写与 disconnect）。
     * 供需要流式读的场景（写文件、decode bounds）复用同一套 UA/超时/Referer 配置。
     */
    @Throws(IOException::class)
    fun open(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000,
        referer: String = DEFAULT_REFERER
    ): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", referer)
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }

    /**
     * GET 请求，返回响应体字符串；任何异常返回 null。
     * @param url 完整 URL
     * @param extraHeaders 额外请求头（如 Cookie）
     * @param timeoutMs 连接/读取超时
     * @param referer Referer 请求头
     */
    fun get(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000,
        referer: String = DEFAULT_REFERER
    ): String? {
        return try {
            getBody(url, extraHeaders, timeoutMs, referer)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * GET 请求，返回响应体字符串；网络异常抛 [IOException]。
     * 供需要区分「网络错误」与「响应解析错误」的调用方（如 BilibiliApi）使用。
     */
    @Throws(IOException::class)
    fun getBody(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000,
        referer: String = DEFAULT_REFERER
    ): String {
        val connection = open(url, extraHeaders, timeoutMs, referer)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
