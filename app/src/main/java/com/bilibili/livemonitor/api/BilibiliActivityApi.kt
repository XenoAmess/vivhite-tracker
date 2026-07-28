package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.util.WbiSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * B 站用户活动监控 API：三源统一接口。
 *
 * - [fetchLatestVideo]：投稿视频列表（wbi 签名）
 * - [fetchPinnedVideo]：置顶视频（无需 wbi）
 * - [fetchLatestDynamic]：动态流（wbi 签名 + buvid3 cookie，风控脆弱）
 *
 * 所有方法返回 sealed class [ActivityResult]，含成功/无数据/错误三态。
 * 错误不抛异常，调用方据此决定是否降级/重试。
 */
open class BilibiliActivityApi {

    sealed class ActivityResult<out T> {
        data class Ok<T>(val data: T) : ActivityResult<T>()
        object NoData : ActivityResult<Nothing>()
        data class Err(val reason: String) : ActivityResult<Nothing>()
    }

    data class VideoInfo(val aid: Long, val title: String)
    data class DynamicInfo(val id: String, val displayText: String)

    /**
     * 拉取最新投稿视频（列表第一个）。
     * 需 wbi 签名。返回 NoData 表示该 UP 无任何投稿。
     */
    suspend fun fetchLatestVideo(
        mid: Long,
        prefs: PreferenceManager
    ): ActivityResult<VideoInfo> = withContext(Dispatchers.IO) {
        if (!WbiSigner.refreshKeysIfNeeded(prefs)) {
            return@withContext ActivityResult.Err("wbi key refresh failed")
        }
        val imgKey = prefs.getWbiImgKey()
        val subKey = prefs.getWbiSubKey()

        val params = mapOf(
            "mid" to mid.toString(),
            "ps" to "1",       // 只取 1 条
            "pn" to "1",       // 第 1 页
            "order" to "pubdate"  // 按发布时间倒序
        )
        val signed = WbiSigner.sign(params, imgKey, subKey)
        val query = signed.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val url = "https://api.bilibili.com/x/space/wbi/arc/search?$query"

        // space API 需要 Referer 为 space.bilibili.com/{mid}，否则触发风控
        val json = HttpClient.get(
            url,
            mapOf("Referer" to "https://space.bilibili.com/$mid")
        ) ?: return@withContext ActivityResult.Err("network error")
        parseVideoList(json)
    }

    /**
     * 拉取置顶视频。无需 wbi 签名。
     * 返回 NoData 表示该 UP 未设置置顶视频。
     */
    suspend fun fetchPinnedVideo(mid: Long): ActivityResult<VideoInfo> = withContext(Dispatchers.IO) {
        val url = "https://api.bilibili.com/x/space/top/arc?vmid=$mid"
        val json = HttpClient.get(url) ?: return@withContext ActivityResult.Err("network error")
        parsePinnedVideo(json)
    }

    /**
     * 拉取最新动态（feed/space 第一条）。
     * 需 wbi 签名 + buvid3 cookie。风控脆弱，失败时返回 Err，调用方静默降级。
     */
    suspend fun fetchLatestDynamic(
        mid: Long,
        prefs: PreferenceManager
    ): ActivityResult<DynamicInfo> = withContext(Dispatchers.IO) {
        var buvid3 = prefs.getBuvid3()
        if (buvid3.isBlank()) {
            // 首次需要 buvid3，从 B 站首页自动获取
            buvid3 = HttpClient.fetchCookie("https://www.bilibili.com/", "buvid3") ?: ""
            if (buvid3.isNotBlank()) {
                prefs.setBuvid3(buvid3)
                AppLogger.d(TAG, "buvid3 auto-fetched: ${buvid3.take(16)}...")
            } else {
                return@withContext ActivityResult.Err("buvid3 fetch failed")
            }
        }
        if (!WbiSigner.refreshKeysIfNeeded(prefs)) {
            return@withContext ActivityResult.Err("wbi key refresh failed")
        }
        val imgKey = prefs.getWbiImgKey()
        val subKey = prefs.getWbiSubKey()

        val params = mapOf("host_mid" to mid.toString())
        val signed = WbiSigner.sign(params, imgKey, subKey)
        val query = signed.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?$query"

        val json = HttpClient.get(
            url,
            mapOf(
                "Cookie" to "buvid3=$buvid3",
                "Referer" to "https://space.bilibili.com/$mid"
            )
        ) ?: return@withContext ActivityResult.Err("network error")
        parseDynamicFeed(json)
    }

    // ========== 解析（internal 便于单测）==========

    internal fun parseVideoList(json: String): ActivityResult<VideoInfo> {
        return try {
            val obj = JSONObject(json)
            val code = obj.optInt("code", -1)
            if (code != 0) {
                return ActivityResult.Err("api code=$code message=${obj.optString("message")}")
            }
            val data = obj.optJSONObject("data") ?: return ActivityResult.Err("missing data")
            val vlist = data.optJSONObject("list")?.optJSONArray("vlist")
                ?: return ActivityResult.NoData
            if (vlist.length() == 0) return ActivityResult.NoData
            val first = vlist.optJSONObject(0) ?: return ActivityResult.NoData
            val aid = first.optLong("aid", -1)
            val title = first.optString("title")
            if (aid <= 0) ActivityResult.NoData else ActivityResult.Ok(VideoInfo(aid, title))
        } catch (e: Exception) {
            AppLogger.w(TAG, "parseVideoList failed: ${e.message}")
            ActivityResult.Err("parse error: ${e.javaClass.simpleName}")
        }
    }

    internal fun parsePinnedVideo(json: String): ActivityResult<VideoInfo> {
        return try {
            val obj = JSONObject(json)
            val code = obj.optInt("code", -1)
            if (code != 0) return ActivityResult.Err("api code=$code")
            val data = obj.optJSONObject("data") ?: return ActivityResult.NoData
            val arc = data.optJSONObject("arc") ?: return ActivityResult.NoData
            val aid = arc.optLong("aid", -1)
            val title = arc.optString("title")
            if (aid <= 0) ActivityResult.NoData else ActivityResult.Ok(VideoInfo(aid, title))
        } catch (e: Exception) {
            ActivityResult.Err("parse error: ${e.javaClass.simpleName}")
        }
    }

    internal fun parseDynamicFeed(json: String): ActivityResult<DynamicInfo> {
        return try {
            val obj = JSONObject(json)
            val code = obj.optInt("code", -1)
            if (code != 0) {
                return ActivityResult.Err("api code=$code message=${obj.optString("message")}")
            }
            val data = obj.optJSONObject("data") ?: return ActivityResult.Err("missing data")
            val items = data.optJSONArray("items") ?: return ActivityResult.NoData
            if (items.length() == 0) return ActivityResult.NoData
            val first = items.optJSONObject(0) ?: return ActivityResult.NoData
            val id = first.optString("id_str").takeIf { it.isNotBlank() }
                ?: first.optString("id")
            if (id.isBlank()) return ActivityResult.NoData
            // 提取展示文本：modules.module_dynamic.desc.text 或标题
            val displayText = extractDynamicDisplayText(first)
            ActivityResult.Ok(DynamicInfo(id, displayText))
        } catch (e: Exception) {
            AppLogger.w(TAG, "parseDynamicFeed failed: ${e.message}")
            ActivityResult.Err("parse error: ${e.javaClass.simpleName}")
        }
    }

    private fun extractDynamicDisplayText(item: JSONObject): String {
        return try {
            val modules = item.optJSONObject("modules") ?: return ""
            val moduleDynamic = modules.optJSONObject("module_dynamic") ?: return ""
            val desc = moduleDynamic.optJSONObject("desc")
            if (desc != null) {
                desc.optString("text").takeIf { it.isNotBlank() } ?: ""
            } else {
                // 无 desc（如纯视频动态），取 module_dynamic.major 的标题
                val major = moduleDynamic.optJSONObject("major") ?: return ""
                major.optString("title").ifBlank { major.optString("archive", "").ifBlank { "" } }
            }
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "BilibiliActivityApi"

        // 白绮的 B 站 UID（与房间号 11258892 同策略：硬编码多处，改 mid 要全改）
        const val MONITOR_MID = 251990176L
    }
}
