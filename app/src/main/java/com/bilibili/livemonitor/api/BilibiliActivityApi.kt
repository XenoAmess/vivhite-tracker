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
     * 直播开播/预告（DYNAMIC_TYPE_LIVE_RCMD）。
     * desktop feed 的字段形态以结构化 live_rcmd 为准，解析不到 liveStartMs 时调用方安全降级不提醒。
     */
    data class LiveRcmdInfo(
        val dynamicId: String,
        val liveStartMs: Long?,
        val title: String?,
        val contentText: String?
    )

    /**
     * 动态条目（feed/space 第一条）。
     * @param id 动态 id_str，用于去重
     * @param type DYNAMIC_TYPE_AV / DYNAMIC_TYPE_DRAW / DYNAMIC_TYPE_FORWARD / DYNAMIC_TYPE_ARTICLE
     * @param displayText 文本内容（图文动态为正文，AV type 为空）
     * @param avItem 该动态附带的视频（DYNAMIC_TYPE_AV 时存在；其他类型为 null）
     * @param isTop 是否置顶
     * @param pubTs 发布时间戳（秒）
     * @param pinnedAvItem 当前置顶的视频。它和 latest dynamic 是两条独立语义，避免
     * 最新非置顶动态掩盖置顶变更。
     * @param latestAvItem feed 中最新的非置顶视频。最新动态为图文时，视频监控仍可
     * 正确推进投稿基线。
     * @param liveRcmd 本页中的直播开播/预告条目（可为 null）
     */
    data class DynamicInfo(
        val id: String,
        val type: String,
        val displayText: String,
        val avItem: AvItem?,
        val isTop: Boolean,
        val pubTs: Long,
        val pinnedAvItem: AvItem? = null,
        val latestAvItem: AvItem? = null,
        val liveRcmd: LiveRcmdInfo? = null
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
            // 置顶动态恒居 items[0]，不能把它当作最新动态；但置顶视频本身也必须
            // 单独保留，否则下层永远无法感知置顶变更。
            var firstErr: ActivityResult.Err? = null
            var latest: DynamicInfo? = null
            var fallback: DynamicInfo? = null
            var pinnedAvItem: AvItem? = null
            var latestAvItem: AvItem? = null
            var liveRcmd: LiveRcmdInfo? = null
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                parseLiveRcmd(item)?.let { liveRcmd = it }
                // 直播预告不是内容动态，不进动态基线（避免触发"新动态"误报）
                if (item.optString("type") == "DYNAMIC_TYPE_LIVE_RCMD") continue
                val info = parseDynamicItem(item)
                if (info == null) {
                    if (firstErr == null) firstErr = ActivityResult.Err("parse failed: missing id")
                    continue
                }
                if (fallback == null) fallback = info
                if (info.isTop) {
                    pinnedAvItem = pinnedAvItem ?: info.avItem
                    continue
                }
                latestAvItem = latestAvItem ?: info.avItem
                if (latest == null) latest = info
            }
            // 全是置顶时仍回退该项以落动态基线；正常场景返回最新非置顶项，并携带
            // 独立的置顶视频信息与直播预告条目。
            val result = latest ?: fallback
            if (result != null) {
                ActivityResult.Ok(
                    result.copy(
                        pinnedAvItem = pinnedAvItem,
                        latestAvItem = latestAvItem,
                        liveRcmd = liveRcmd
                    )
                )
            } else {
                firstErr ?: ActivityResult.Err("parse failed: missing id")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "parseDynamicFeed failed: ${e.message}")
            ActivityResult.Err("parse error: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 解析直播开播/预告条目（DYNAMIC_TYPE_LIVE_RCMD）。
     * live_start_time 可能是毫秒或秒级时间戳；解析不到时返回 null（调用方安全降级）。
     * 字段形态：结构化 live_rcmd（live_start_time/title/content），解析失败不抛异常。
     */
    internal fun parseLiveRcmd(item: JSONObject): LiveRcmdInfo? {
        return try {
            if (item.optString("type") != "DYNAMIC_TYPE_LIVE_RCMD") return null
            val id = item.optString("id_str").takeIf { it.isNotBlank() }
                ?: item.optString("id").takeIf { it.isNotBlank() } ?: return null
            val dyn = flattenModules(item)["module_dynamic"]
            val lr = dyn?.optJSONObject("live_rcmd")
            val rawStart = lr?.optLong("live_start_time", 0L)?.takeIf { it > 0 }
            val startMs = rawStart?.let { if (it > 10_000_000_000L) it else it * 1000L }
            LiveRcmdInfo(
                dynamicId = id,
                liveStartMs = startMs,
                title = lr?.optString("title")?.takeIf { it.isNotBlank() },
                contentText = lr?.optString("content")?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            null
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
     * 提取动态展示文本。线上真实结构（2026-08-02 实测）是
     * module_desc.text 直挂 + rich_text_nodes；老 fixture/文档形态
     * 是嵌套 module_desc.desc.text。先直挂后嵌套，两种都兼容。
     */
    private fun extractDisplayText(modules: Map<String, JSONObject>): String {
        val moduleDesc = modules["module_desc"] ?: return ""
        moduleDesc.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        return moduleDesc.optJSONObject("desc")
            ?.optString("text")?.takeIf { it.isNotBlank() } ?: ""
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

        // 白绮的 B 站 UID（单一来源见 util/BiliTargets）
        const val MONITOR_MID = com.bilibili.livemonitor.util.BiliTargets.MONITOR_MID
    }
}
