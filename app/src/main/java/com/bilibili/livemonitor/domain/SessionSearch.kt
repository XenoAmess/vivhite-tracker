package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity

/**
 * 手账搜索（纯函数）：场次标题 + 心情事件的标题/原因/备注/心情文案，
 * 大小写不敏感子串匹配，结果按时间倒序。
 */
object SessionSearch {

    enum class Kind { SESSION, MOOD }

    data class Hit(
        val ts: Long,
        val kind: Kind,
        val text: String
    )

    fun search(
        sessions: List<StreamSessionEntity>,
        moods: List<MoodEventEntity>,
        query: String
    ): List<Hit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        sessions.forEach { s ->
            if (s.title?.lowercase()?.contains(q) == true) {
                hits += Hit(s.startTs, Kind.SESSION, s.title!!)
            }
        }
        moods.forEach { m ->
            val haystack = listOfNotNull(m.title, m.reason, m.note, MoodCatalog.display(m.mood))
                .joinToString("\n").lowercase()
            if (haystack.contains(q)) {
                hits += Hit(
                    m.eventTs, Kind.MOOD,
                    "${MoodCatalog.display(m.mood)} · ${m.title}"
                )
            }
        }
        return hits.sortedByDescending { it.ts }
    }
}
