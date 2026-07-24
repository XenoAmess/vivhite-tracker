package com.bilibili.livemonitor.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

open class BilibiliApi : LiveStatusChecker {

    sealed class LiveStatus {
        object Live : LiveStatus()
        object NotLive : LiveStatus()
        data class Error(val reason: String) : LiveStatus()
    }

    override suspend fun checkLiveStatus(roomId: Long): LiveStatus = withContext(Dispatchers.IO) {
        when (val apiResult = checkByApi(roomId)) {
            is LiveStatus.Live, is LiveStatus.NotLive -> apiResult
            is LiveStatus.Error -> {
                // API失败时尝试网页解析兜底
                val webResult = checkByWebPage(roomId)
                if (webResult is LiveStatus.Error) apiResult else webResult
            }
        }
    }

    // internal open：测试可注入 fake 实现验证兜底编排
    internal open suspend fun checkByApi(roomId: Long): LiveStatus {
        return try {
            val url = URL("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId")
            val connection = url.openConnection() as HttpsURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://live.bilibili.com/")
                connectTimeout = 5000
                readTimeout = 5000
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            parseApiResponse(response)
        } catch (e: IOException) {
            LiveStatus.Error("api network error: ${e.message}")
        } catch (e: Exception) {
            LiveStatus.Error("api error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // internal open：测试可注入 fake 实现验证兜底编排
    internal open suspend fun checkByWebPage(roomId: Long): LiveStatus {
        return try {
            val url = "https://live.bilibili.com/$roomId"
            val doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .referrer("https://www.bilibili.com/")
                .timeout(5000)
                .get()

            // 查找直播状态相关信息
            val scripts = doc.select("script")
            for (script in scripts) {
                val status = parseScriptContent(script.data())
                if (status != null) return status
            }

            // 备用方法：检查页面上的开播标识
            val liveBadge = doc.select(".live-status, .living-icon, [class*='live'], [class*='living']")
            if (liveBadge.isNotEmpty()) LiveStatus.Live else LiveStatus.NotLive
        } catch (e: IOException) {
            LiveStatus.Error("webpage network error: ${e.message}")
        } catch (e: Exception) {
            LiveStatus.Error("webpage error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // live_status: 0=未开播, 1=直播中, 2=轮播中
        internal fun parseApiResponse(response: String): LiveStatus {
            return try {
                val json = JSONObject(response)
                val data = json.optJSONObject("data")
                    ?: return LiveStatus.Error("api response missing data field")
                val liveStatus = data.optInt("live_status", 0)
                if (liveStatus == 1) LiveStatus.Live else LiveStatus.NotLive
            } catch (e: Exception) {
                LiveStatus.Error("api parse error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 返回null表示脚本中未找到状态信息
        internal fun parseScriptContent(text: String): LiveStatus? {
            if (!text.contains("live_status") && !text.contains("\"status\":")) return null

            val statusMatch = Regex("\"live_status\"\\s*:\\s*(\\d)").find(text)
            if (statusMatch != null) {
                val status = statusMatch.groupValues[1].toIntOrNull() ?: 0
                return if (status == 1) LiveStatus.Live else LiveStatus.NotLive
            }

            val statusMatch2 = Regex("\"status\"\\s*:\\s*\"?([^\"\\s,}]+)\"?").find(text)
            if (statusMatch2 != null) {
                val status = statusMatch2.groupValues[1]
                return if (status == "LIVE" || status == "1" || status == "true") {
                    LiveStatus.Live
                } else {
                    LiveStatus.NotLive
                }
            }
            return null
        }
    }
}
