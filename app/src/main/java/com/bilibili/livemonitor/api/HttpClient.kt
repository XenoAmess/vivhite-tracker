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
        return try {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            body
        } catch (e: Exception) {
            null
        }
    }

    /**
     * GET 请求并从 Set-Cookie 响应头里提取指定 cookie 的值。
     * 用于获取 buvid3 等 B 站首次访问自动种下的 cookie。
     * @return cookie 值（不含 `name=` 前缀和 `; Path=...` 后缀）；未找到返回 null
     */
    fun fetchCookie(url: String, cookieName: String, timeoutMs: Int = 8000): String? {
        return try {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false  // 不跟随重定向，直接读原始响应头
            }
            // 先读响应体（即使不关心内容，也要读完才能可靠拿头）
            try { connection.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) {}
            val cookies = connection.headerFields?.get("Set-Cookie") ?: connection.headerFields?.get("set-cookie")
            connection.disconnect()
            cookies?.find { it.startsWith("$cookieName=") }?.let { cookieHeader ->
                cookieHeader.substringAfter("$cookieName=").substringBefore(';').takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
