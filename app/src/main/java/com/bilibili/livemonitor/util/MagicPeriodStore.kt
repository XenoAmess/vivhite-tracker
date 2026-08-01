package com.bilibili.livemonitor.util

import com.bilibili.livemonitor.domain.MagicPeriod
import org.json.JSONArray
import org.json.JSONObject

/**
 * 魔法期记录的 JSON 持久化（存 PreferenceManager.magicPeriodsJson）。
 * 损坏 JSON 容错回退为空表（不丢 app 其他数据）。
 */
object MagicPeriodStore {

    fun load(prefs: PreferenceManager): List<MagicPeriod> {
        return try {
            val arr = JSONArray(prefs.getMagicPeriodsJson())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val start = obj.optLong("start", -1L)
                val end = obj.optLong("end", -1L)
                if (start > 0 && end > start) MagicPeriod(start, end) else null
            }
        } catch (e: Exception) {
            AppLogger.w("MagicPeriodStore", "magic periods json corrupted, fallback to empty", e)
            emptyList()
        }
    }

    fun save(prefs: PreferenceManager, periods: List<MagicPeriod>) {
        val arr = JSONArray()
        periods.forEach { p ->
            arr.put(JSONObject().apply {
                put("start", p.start)
                put("end", p.end)
            })
        }
        prefs.setMagicPeriodsJson(arr.toString())
    }
}
