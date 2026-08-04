package com.bilibili.livemonitor.api

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 共享 HTTP GET 客户端。无第三方依赖，原生 HttpsURLConnection。
 */
object HttpClient {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * GET 请求，返回响应体字符串；失败返回 null。
     * @param url 完整 URL
     * @param extraHeaders 额外请求头（如 Cookie）
     * @param timeoutMs 连接/读取超时
     */
    fun get(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000
    ): String? {
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpsURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            body
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
