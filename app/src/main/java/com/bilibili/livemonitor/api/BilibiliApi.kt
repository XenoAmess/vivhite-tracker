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

    /**
     * 直播间信息（一次 API 调用同时拿标题、封面、开播状态，避免冗余网络请求）。
     * @param title 直播标题（如 "失眠 无言"），未开播时可能是上次直播的旧标题
     * @param cover 直播间封面 URL，null 时取 FALLBACK_COVER_URL
     * @param live 实时开播状态（分享文案要区分"开播了"和"还没开播"）
     */
    data class RoomInfo(val title: String?, val cover: String?, val live: Boolean)

    /**
     * 取直播间标题 + 封面 + 实时开播状态。返回 null = 网络/API 异常。
     * 替代旧版 fetchRoomCover（标题之前被丢弃了，现在一次请求拿回）。
     */
    suspend fun fetchRoomInfo(roomId: Long): RoomInfo? = withContext(Dispatchers.IO) {
        try {
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
            RoomInfo(
                title = parseRoomTitle(response),
                cover = parseRoomCover(response),
                live = parseApiResponse(response) is LiveStatus.Live
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // internal 便于单测
        internal fun parseRoomTitle(response: String): String? {
            return try {
                val json = JSONObject(response)
                val data = json.optJSONObject("data") ?: return null
                data.optString("title").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        // internal 便于单测：从 get_info 响应里解析封面 URL
        internal fun parseRoomCover(response: String): String? {
            return try {
                val json = JSONObject(response)
                val data = json.optJSONObject("data") ?: return null
                data.optString("user_cover").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

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
