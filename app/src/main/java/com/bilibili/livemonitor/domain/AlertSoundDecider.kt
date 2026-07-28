package com.bilibili.livemonitor.domain

/**
 * 提醒铃声来源（纯数据，无 Android 依赖，便于纯 JVM 单测）。
 *
 * 解析规则见 [AlertSoundDecider.resolve]：prefs 里存的字符串带前缀，
 * 前缀决定来源档位。空串视为 [Default]（内置池第 1 个）。
 */
sealed class SoundSource {
    /** 默认：内置池第 1 个（BuiltInSound.DEFAULT）。 */
    object Default : SoundSource()

    /** 内置池指定项，[key] 对应 BuiltInSound.key。 */
    data class BuiltIn(val key: String) : SoundSource()

    /** 系统铃声库（ACTION_RINGTONE_PICKER 返回的 uri）。 */
    data class System(val uri: String) : SoundSource()

    /** 用户通过 SAF 选择的音频文件 uri（已 takePersistableUriPermission）。 */
    data class File(val uri: String) : SoundSource()
}

/**
 * 提醒铃声源决策（纯函数，无 Android 依赖）。
 *
 * 负责把 prefs 里存的字符串解析成 [SoundSource]，
 * 以及把用户选择编码成字符串存入 prefs。
 *
 * 实际加载（MediaPlayer.setDataSource）和三档兜底在
 * `util/AlertSoundProvider` 里完成；本类只做字符串 ↔ 来源映射。
 */
object AlertSoundDecider {

    private const val PREFIX_BUILTIN = "builtin:"
    private const val PREFIX_SYSTEM = "system:"
    private const val PREFIX_FILE = "file:"

    /**
     * 解析 prefs 里的铃声 uri 字符串。
     *
     * - 空白 → [SoundSource.Default]
     * - `builtin:xxx` → [SoundSource.BuiltIn]（key 未匹配到 enum 时仍返回 BuiltIn，
     *   加载层负责兜底到默认，这里不做 enum 校验保持纯函数）
     * - `system:xxx` → [SoundSource.System]
     * - `file:xxx` → [SoundSource.File]
     * - 未知前缀 → [SoundSource.Default]（向后兼容旧版本 / 损坏数据）
     */
    fun resolve(uriPref: String?): SoundSource {
        if (uriPref.isNullOrBlank()) return SoundSource.Default
        return when {
            uriPref.startsWith(PREFIX_BUILTIN) ->
                SoundSource.BuiltIn(uriPref.removePrefix(PREFIX_BUILTIN))
            uriPref.startsWith(PREFIX_SYSTEM) ->
                SoundSource.System(uriPref.removePrefix(PREFIX_SYSTEM))
            uriPref.startsWith(PREFIX_FILE) ->
                SoundSource.File(uriPref.removePrefix(PREFIX_FILE))
            else -> SoundSource.Default
        }
    }

    fun encodeBuiltIn(key: String): String = "$PREFIX_BUILTIN$key"
    fun encodeSystem(uri: String): String = "$PREFIX_SYSTEM$uri"
    fun encodeFile(uri: String): String = "$PREFIX_FILE$uri"
}
