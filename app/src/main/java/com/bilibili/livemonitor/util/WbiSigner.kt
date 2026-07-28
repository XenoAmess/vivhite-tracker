package com.bilibili.livemonitor.util

import com.bilibili.livemonitor.api.HttpClient
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B 站 wbi 签名实现。
 *
 * 算法：
 * 1. GET `/x/web-interface/nav` 拿 `img_url`/`sub_url`，取文件名得 `img_key`/`sub_key`
 *    （全站统一，每日更替，需缓存）
 * 2. 用固定 64 长 `MIXIN_KEY_ENC_TAB` 置换表对 `img_key+sub_key` 重排取前 32 字符 → `mixin_key`
 * 3. 参数加 `wts`=当前秒级时间戳，按 key 升序拼接为 query（百分号编码、大写、空格 `%20`、过滤 `!'()*`）
 * 4. 末尾拼 `mixin_key`，MD5 取 hex → `w_rid`
 *
 * 无第三方依赖：`java.security.MessageDigest`（MD5）+ `java.net.URLEncoder`。
 *
 * key 缓存：存 prefs，12 小时刷新一次，nav 接口挂了则降级（返回 false，调用方跳过 wbi 签名请求）。
 */
object WbiSigner {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 22, 51, 55, 33, 30, 5, 54, 37, 11, 40, 28, 19, 38, 10,
        13, 39, 63, 59, 1, 61, 56, 42, 30, 0, 21, 26, 49, 9, 36, 7,
        14, 16, 57, 53, 20, 60, 44, 6, 40, 25, 64, 17, 34, 52, 48, 62
    )

    private const val KEY_REFRESH_INTERVAL = 12 * 60 * 60 * 1000L // 12 小时
    private const val NAV_API = "https://api.bilibili.com/x/web-interface/nav"

    /**
     * 刷新 wbi key（如果缓存过期或为空）。
     * @return true = key 已就绪可签名；false = 获取失败，调用方应降级
     */
    suspend fun refreshKeysIfNeeded(prefs: PreferenceManager): Boolean {
        val now = System.currentTimeMillis()
        if (prefs.getWbiImgKey().isNotBlank()
            && prefs.getWbiSubKey().isNotBlank()
            && now - prefs.getWbiKeyUpdatedAt() < KEY_REFRESH_INTERVAL
        ) {
            return true
        }

        val json = HttpClient.get(NAV_API) ?: run {
            AppLogger.w(TAG, "nav API returned null, wbi key refresh failed")
            return false
        }

        val (imgKey, subKey) = parseNavResponse(json) ?: run {
            AppLogger.w(TAG, "nav API response unparseable: ${json.take(200)}")
            return false
        }

        if (imgKey.isBlank() || subKey.isBlank()) {
            AppLogger.w(TAG, "nav API returned empty keys")
            return false
        }

        prefs.setWbiKeys(imgKey, subKey)
        AppLogger.d(TAG, "wbi keys refreshed: imgKey=$imgKey subKey=$subKey")
        return true
    }

    /**
     * 解析 nav 响应，提取 img_key 和 sub_key。
     * wbi_img: {"img_url":"https://i0.hdslb.com/bfs/wbi/xxx.png","sub_url":"https://i0.hdslb.com/bfs/wbi/yyy.png"}
     */
    internal fun parseNavResponse(json: String): Pair<String, String>? {
        return try {
            val obj = org.json.JSONObject(json)
            val data = obj.optJSONObject("data") ?: return null
            val wbiImg = data.optJSONObject("wbi_img") ?: return null
            val imgUrl = wbiImg.optString("img_url").takeIf { it.isNotBlank() } ?: return null
            val subUrl = wbiImg.optString("sub_url").takeIf { it.isNotBlank() } ?: return null
            val imgKey = imgUrl.substringAfterLast('/').substringBeforeLast('.')
            val subKey = subUrl.substringAfterLast('/').substringBeforeLast('.')
            imgKey to subKey
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 对参数进行 wbi 签名，返回包含 `wts` 和 `w_rid` 的完整参数 map。
     */
    fun sign(params: Map<String, String>, imgKey: String, subKey: String): Map<String, String> {
        val mixinKey = getMixinKey(imgKey + subKey)
        val wts = (System.currentTimeMillis() / 1000).toString()
        val signed = params.toMutableMap().apply { put("wts", wts) }

        // 按 key 升序拼接，百分号编码（大写、空格 %20、过滤 !'()*）
        val query = signed.toSortedMap().entries.joinToString("&") { (k, v) ->
            "$k=${encodeValue(v)}"
        }
        val wRid = md5Hex("$query$mixinKey")
        signed["w_rid"] = wRid
        return signed
    }

    /**
     * 用 MIXIN_KEY_ENC_TAB 置换表对 raw key 重排，取前 32 字符。
     */
    internal fun getMixinKey(raw: String): String {
        val result = StringBuilder(32)
        for (i in 0 until 32) {
            result.append(raw[MIXIN_KEY_ENC_TAB[i]])
        }
        return result.toString()
    }

    /**
     * 百分号编码：大写、空格用 %20、过滤 !'()*。
     */
    private fun encodeValue(value: String): String {
        val encoded = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%2A", "*")
        // 确保百分号编码大写（URLEncoder 已是大写，但以防万一）
        return encoded
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val TAG = "WbiSigner"
}
