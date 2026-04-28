package com.bilibili.livemonitor.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class BilibiliApi {

    suspend fun isLiveStreaming(roomId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // 方法1: 使用B站API
            val apiResult = checkByApi(roomId)
            if (apiResult != null) return@withContext apiResult
            
            // 方法2: 使用网页解析作为备用
            return@withContext checkByWebPage(roomId)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun checkByApi(roomId: Long): Boolean? {
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

            val json = JSONObject(response)
            val data = json.optJSONObject("data") ?: return null
            
            // live_status: 0=未开播, 1=直播中, 2=轮播中
            val liveStatus = data.optInt("live_status", 0)
            return liveStatus == 1
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun checkByWebPage(roomId: Long): Boolean {
        try {
            val url = "https://live.bilibili.com/$roomId"
            val doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .referrer("https://www.bilibili.com/")
                .timeout(5000)
                .get()

            // 查找直播状态相关信息
            val scripts = doc.select("script")
            for (script in scripts) {
                val text = script.data()
                if (text.contains("live_status") || text.contains("\"status\":")) {
                    // 尝试从脚本中提取状态
                    val statusMatch = Regex("\"live_status\"\\s*:\\s*(\\d)").find(text)
                    if (statusMatch != null) {
                        val status = statusMatch.groupValues[1].toIntOrNull() ?: 0
                        return status == 1
                    }
                    
                    val statusMatch2 = Regex("\"status\"\\s*:\\s*\"?([^\"\\s,}]+)\"?").find(text)
                    if (statusMatch2 != null) {
                        val status = statusMatch2.groupValues[1]
                        return status == "LIVE" || status == "1" || status == "true"
                    }
                }
            }

            // 备用方法：检查页面上的开播标识
            val liveBadge = doc.select(".live-status, .living-icon, [class*='live'], [class*='living']")
            return liveBadge.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
