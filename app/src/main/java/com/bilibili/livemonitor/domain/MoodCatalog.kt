package com.bilibili.livemonitor.domain

/**
 * 心情目录：key → (emoji, 中文文案, 分组)，三组按 积极→中性→消极 排序。
 * DB 只存 key；emoji/文案以后调整不影响历史数据。
 * emoji 均避开 Android 低版本（minSdk 26）显示不了的较新字符（Emoji ≤ 12）。
 */
object MoodCatalog {

    enum class Group { POSITIVE, NEUTRAL, NEGATIVE }

    data class Mood(val key: String, val emoji: String, val label: String, val group: Group)

    val moods: List<Mood> = listOf(
        // 积极
        Mood("happy", "😄", "开心", Group.POSITIVE),
        Mood("excited", "🤩", "兴奋", Group.POSITIVE),
        Mood("moved", "🥰", "感动", Group.POSITIVE),
        Mood("relaxed", "😌", "放松", Group.POSITIVE),
        Mood("content", "😊", "满足", Group.POSITIVE),
        Mood("healed", "🤗", "治愈", Group.POSITIVE),
        Mood("grateful", "🙏", "感激", Group.POSITIVE),
        Mood("expecting", "🤤", "期待", Group.POSITIVE),
        // 中性
        Mood("calm", "🙂", "平静", Group.NEUTRAL),
        Mood("indifferent", "😐", "无感", Group.NEUTRAL),
        Mood("tired", "😪", "疲惫", Group.NEUTRAL),
        Mood("sleepy", "😴", "困倦", Group.NEUTRAL),
        Mood("empty", "😶", "放空", Group.NEUTRAL),
        // 消极
        Mood("sad", "😢", "难过", Group.NEGATIVE),
        Mood("breakdown", "😭", "崩溃", Group.NEGATIVE),
        Mood("angry", "😡", "生气", Group.NEGATIVE),
        Mood("annoyed", "😤", "烦躁", Group.NEGATIVE),
        Mood("anxious", "😰", "焦虑", Group.NEGATIVE),
        Mood("depressed", "😔", "沮丧", Group.NEGATIVE),
        Mood("wronged", "🥺", "委屈", Group.NEGATIVE),
        Mood("heartbroken", "💔", "心碎", Group.NEGATIVE),
        Mood("worried", "😟", "忧虑", Group.NEGATIVE)
    )

    private val byKey: Map<String, Mood> = moods.associateBy { it.key }
    private val byDisplay: Map<String, Mood> = moods.associateBy { it.emoji + it.label }

    fun find(key: String): Mood? = byKey[key]

    /** 列表展示用：「😄开心」；未知 key 兜底原样显示 */
    fun display(key: String): String = byKey[key]?.let { it.emoji + it.label } ?: key

    /** CSV 导入反查：「😄开心」→ happy；裸 key/不认识的原样透传（display 会兜底展示） */
    fun keyOf(displayOrKey: String): String = byDisplay[displayOrKey]?.key ?: displayOrKey
}
