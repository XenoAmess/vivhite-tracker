package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * B 站用户活动监控 API。
 *
 * 核心端点：`/x/polymer/web-dynamic/desktop/v1/feed/space`（桌面端动态流）。
 *
 * **关键发现**：此端点对未登录完全开放（2026-07 实测），返回内容含
 * - 投稿视频（DYNAMIC_TYPE_AV，含 aid/bvid/title/cover/play/like）
 * - 图文动态（DYNAMIC_TYPE_DRAW）
 * - 转发动态（DYNAMIC_TYPE_FORWARD）
 * - 专栏（DYNAMIC_TYPE_ARTICLE）
 * - 置顶标记（module_author.is_top）
 *
 * 一个 API 调用同时覆盖"视频列表 + 动态流 + 置顶"三个监控功能。
 *
 * 历史端点（已不再使用）：
 * - `/x/space/wbi/arc/search`：需要 wbi 签名 + dm_img 风控，未登录 -403
 * - `/x/polymer/web-dynamic/v1/feed/space`（移动端）：需要 buvid3 + wbi + dm_img，未登录 HTTP 412
 */
open class BilibiliActivityApi {

    sealed class ActivityResult<out T> {
        data class Ok<T>(val data: T) : ActivityResult<T>()
        object NoData : ActivityResult<Nothing>()
        data class Err(val reason: String) : ActivityResult<Nothing>()
    }

    /**
     * 投稿视频条目（来自 DYNAMIC_TYPE_AV 或 DYNAMIC_TYPE_ARCHIVE）。
     */
    data class AvItem(
        val aid: Long,
        val title: String,
        val bvid: String,
        val durationText: String,
        val cover: String,
        val playCount: Long,
        val likeCount: Long
    )

    /**
     * 动态条目（feed/space 第一条）。
     * @param id 动态 id_str，用于去重
     * @param type DYNAMIC_TYPE_AV / DYNAMIC_TYPE_DRAW / DYNAMIC_TYPE_FORWARD / DYNAMIC_TYPE_ARTICLE
     * @param displayText 文本内容（图文动态为正文，AV type 为空）
     * @param avItem 该动态附带的视频（DYNAMIC_TYPE_AV 时存在；其他类型为 null）
     * @param isTop 是否置顶
     * @param pubTs 发布时间戳（秒）
     */
    data class DynamicInfo(
        val id: String,
        val type: String,
        val displayText: String,
        val avItem: AvItem?,
        val isTop: Boolean,
        val pubTs: Long
    )

    /**
     * 拉取最新动态（desktop 端点，未登录可用）。
     * 返回 NoData 表示该 UP 无任何动态（不太可能但保留语义）。
     *
     * open：测试可注入 fake 响应，验证活动监控提醒编排
     * （新视频/动态/置顶去重与通知触发）。
     */
    open suspend fun fetchLatestDynamic(mid: Long): ActivityResult<DynamicInfo> = withContext(Dispatchers.IO) {
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/desktop/v1/feed/space?host_mid=$mid&features=itemOpusStyle"
        val json = HttpClient.get(url) ?: return@withContext ActivityResult.Err("network error")
        parseDynamicFeed(json)
    }

    // ========== 解析（internal 便于单测）==========

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
            // 跳过置顶：置顶动态恒居 items[0]，只取第 0 条会让 last_dynamic_id
            // 永远是置顶那条，新动态全部漏检（2026-08-02 线上实锤：
            // 置顶 id=896036023158439940 占位，当日新动态在 items[1] 不可见）
            var firstErr: ActivityResult.Err? = null
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val info = parseDynamicItem(item)
                if (info == null) {
                    if (firstErr == null) firstErr = ActivityResult.Err("parse failed: missing id")
                    continue
                }
                if (info.isTop) continue
                return ActivityResult.Ok(info)
            }
            // 全是置顶（用户只置顶不发新内容）：回退用第 0 条，保证 baseline 能落
            val fallback = items.optJSONObject(0)?.let { parseDynamicItem(it) }
            if (fallback != null) {
                ActivityResult.Ok(fallback)
            } else {
                firstErr ?: ActivityResult.Err("parse failed: missing id")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "parseDynamicFeed failed: ${e.message}")
            ActivityResult.Err("parse error: ${e.javaClass.simpleName}")
        }
    }

    /**
     * modules 字段兼容层：线上 desktop 端点实际返回 JSONArray
     * （单键对象列表 [{"module_author":{...}},{"module_dynamic":{...}}]），
     * 老 fixture/文档形态是 JSONObject。统一合并为 key -> JSONObject 视图。
     */
    private fun flattenModules(item: JSONObject): Map<String, JSONObject> {
        val result = mutableMapOf<String, JSONObject>()
        when (val modules = item.opt("modules")) {
            is JSONObject -> {
                for (key in modules.keys()) {
                    (modules.optJSONObject(key))?.let { result[key] = it }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until modules.length()) {
                    val entry = modules.optJSONObject(i) ?: continue
                    for (key in entry.keys()) {
                        (entry.optJSONObject(key))?.let { result[key] = it }
                    }
                }
            }
        }
        return result
    }

    /**
     * 解析单条动态。返回 null 表示无法解析（id 缺失等）。
     */
    internal fun parseDynamicItem(item: JSONObject): DynamicInfo? {
        val id = item.optString("id_str").takeIf { it.isNotBlank() }
            ?: item.optString("id").takeIf { it.isNotBlank() }
            ?: return null
        val type = item.optString("type")
        val modules = flattenModules(item)
        val moduleAuthor = modules["module_author"]
        val isTop = moduleAuthor?.optBoolean("is_top", false) ?: false
        val pubTs = moduleAuthor?.optLong("pub_ts", 0L) ?: 0L
        val displayText = extractDisplayText(modules)
        val avItem = extractAvItem(modules)
        return DynamicInfo(id, type, displayText, avItem, isTop, pubTs)
    }

    /**
     * 提取动态展示文本：
     * - module_desc.text（DYNAMIC_TYPE_DRAW 等带 desc 的类型）
     * - 空字符串（DYNAMIC_TYPE_AV 无 desc 时）
     */
    private fun extractDisplayText(modules: Map<String, JSONObject>): String {
        val moduleDesc = modules["module_desc"] ?: return ""
        val text = moduleDesc.optJSONObject("desc")?.optString("text")
        return text?.takeIf { it.isNotBlank() } ?: ""
    }

    /**
     * 提取 DYNAMIC_TYPE_AV / DYNAMIC_TYPE_ARCHIVE 的视频条目。
     * 其他类型返回 null。
     */
    private fun extractAvItem(modules: Map<String, JSONObject>): AvItem? {
        return try {
            val moduleDynamic = modules["module_dynamic"] ?: return null
            val archive = moduleDynamic.optJSONObject("dyn_archive")
                ?: moduleDynamic.optJSONObject("archive")
                ?: return null
            val aidStr = archive.optString("aid").takeIf { it.isNotBlank() } ?: return null
            val aid = aidStr.toLongOrNull() ?: return null
            val title = archive.optString("title")
            val bvid = archive.optString("bvid")
            val durationText = archive.optString("duration_text")
            val cover = archive.optString("cover")
            val stat = archive.optJSONObject("stat")
            val playCount = stat?.optLong("play", 0L) ?: 0L
            val likeCount = stat?.optLong("like", 0L) ?: 0L
            AvItem(aid, title, bvid, durationText, cover, playCount, likeCount)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "BilibiliActivityApi"

        // 白绮的 B 站 UID（与房间号 11258892 同策略：硬编码多处，改 mid 要全改）
        const val MONITOR_MID = 251990176L
    }
}