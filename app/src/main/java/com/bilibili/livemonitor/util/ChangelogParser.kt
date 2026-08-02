package com.bilibili.livemonitor.util

/**
 * CHANGELOG.txt（构建时由 git tag 生成）解析。
 * 格式：
 *   ## v1.6.0 (2026-08-02)
 *   d0f09c9 fix(service): xxx
 *   b045247 feat(magic): xxx
 *
 *   ## v1.5.1 (2026-07-30)
 *   ...
 * 乱格式行跳过（容错），空输入返回空列表（调用方显示兜底文案）。
 */
object ChangelogParser {

    data class ReleaseNote(
        val tag: String,
        val date: String,
        val lines: List<String>
    )

    private val HEADER = Regex("##\\s+(\\S+)\\s*\\(([^)]*)\\)")

    fun parse(text: String): List<ReleaseNote> {
        val result = mutableListOf<ReleaseNote>()
        var tag: String? = null
        var date = ""
        val lines = mutableListOf<String>()

        fun flush() {
            tag?.let { result.add(ReleaseNote(it, date, lines.toList())) }
            tag = null; date = ""; lines.clear()
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val header = HEADER.matchEntire(line)
            if (header != null) {
                flush()
                tag = header.groupValues[1]
                date = header.groupValues[2]
            } else if (tag != null) {
                lines.add(line)
            }
            // tag 前的游离行（如兜底文案）忽略
        }
        flush()
        return result
    }
}
