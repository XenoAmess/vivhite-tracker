package com.bilibili.livemonitor.util

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.domain.AlertSoundDecider
import com.bilibili.livemonitor.domain.SoundSource

/**
 * 内置铃声池。资源文件在 `res/raw/`，音频内容由开发者提供
 * （当前为占位 beep，后续替换为真实铃声）。
 *
 * [DEFAULT] 是无用户选择时的默认值，也是加载失败的第一档兜底。
 */
enum class BuiltInSound(val key: String, val resId: Int, val title: String) {
    CL_1("alert_1", R.raw.alert_1, "海愿"),
    CL_2("alert_2", R.raw.alert_2, "春弦"),
    CL_3("alert_3", R.raw.alert_3, "Ad astra"),
    CL_4("alert_4", R.raw.alert_4, "BATTLEPLAN ARCLIGHT"),
    CL_5("alert_5", R.raw.alert_5, "星之所在"),
    CL_6("alert_6", R.raw.alert_6, "遊園施設");

    companion object {
        val DEFAULT: BuiltInSound = CL_1

        fun fromKey(key: String?): BuiltInSound? = values().firstOrNull { it.key == key }
    }
}

/**
 * 把 [MediaPlayer] 设置到正确的铃声数据源，含三档兜底：
 *
 * 1. 用户选择的（file / system / builtin）→ 尝试加载
 * 2. 失败 → 内置默认（[BuiltInSound.DEFAULT]）
 * 3. 再失败 → 系统闹钟铃声（保留原有降级链 TYPE_ALARM → NOTIFICATION → RINGTONE）
 * 4. 全部失败 → 返回 false，调用方静默处理
 *
 * 每级失败打 AppLogger 日志，方便用户从「运行日志」页导出排查。
 *
 * 用法：
 * ```
 * val player = MediaPlayer()
 * if (!provider.setupDataSource(context, player, prefs.getAlertSoundUri())) {
 *     player.release()
 *     return
 * }
 * player.setAudioAttributes(...)
 * player.isLooping = true
 * player.prepare()
 * player.start()
 * ```
 */
class AlertSoundProvider {

    /**
     * @param context 用于取 resources / contentResolver
     * @param player 要设置数据源的 MediaPlayer（调用方负责后续 prepare/start/release）
     * @param uriPref prefs 里的原始字符串（见 [AlertSoundDecider.resolve]）
     * @return true = 数据源已就绪，调用方可继续 prepare；false = 全部兜底失败，调用方应放弃
     */
    fun setupDataSource(context: Context, player: MediaPlayer, uriPref: String?): Boolean {
        val source = AlertSoundDecider.resolve(uriPref)

        // 第一选择
        if (trySetup(context, player, source)) return true

        // 兜底 1：内置默认（除非第一选择就是默认，避免重复尝试）
        if (source !is SoundSource.Default) {
            AppLogger.w(TAG, "primary source failed, fallback to builtin default")
            if (setupBuiltin(context, player, BuiltInSound.DEFAULT)) return true
        }

        // 兜底 2：系统闹钟铃声
        AppLogger.w(TAG, "builtin failed, fallback to system alarm")
        return setupSystemAlarm(context, player)
    }

    private fun trySetup(context: Context, player: MediaPlayer, source: SoundSource): Boolean {
        return when (source) {
            is SoundSource.Default -> setupBuiltin(context, player, BuiltInSound.DEFAULT)
            is SoundSource.BuiltIn -> {
                val sound = BuiltInSound.fromKey(source.key)
                if (sound == null) {
                    AppLogger.w(TAG, "unknown builtin key: ${source.key}, using default")
                    setupBuiltin(context, player, BuiltInSound.DEFAULT)
                } else {
                    setupBuiltin(context, player, sound)
                }
            }
            is SoundSource.System -> setupUri(context, player, Uri.parse(source.uri))
            is SoundSource.File -> setupUri(context, player, Uri.parse(source.uri))
        }
    }

    private fun setupBuiltin(context: Context, player: MediaPlayer, sound: BuiltInSound): Boolean {
        return try {
            // 用 android.resource:// uri 方式而不是 AssetFileDescriptor：
            // 1. 更简洁，不需要手动 close AFD
            // 2. Robolectric 的 ShadowMediaPlayer 对 setDataSource(Context, Uri) 支持更好
            val uri = Uri.parse("android.resource://${context.packageName}/${sound.resId}")
            player.setDataSource(context, uri)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "setup builtin ${sound.key} failed", e)
            false
        }
    }

    private fun setupUri(context: Context, player: MediaPlayer, uri: Uri): Boolean {
        return try {
            player.setDataSource(context, uri)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "setup uri $uri failed", e)
            false
        }
    }

    private fun setupSystemAlarm(context: Context, player: MediaPlayer): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: run {
                AppLogger.e(TAG, "no system ringtone available at all")
                return false
            }
        return setupUri(context, player, uri)
    }

    companion object {
        private const val TAG = "AlertSoundProvider"
    }
}
