package com.bilibili.livemonitor.util

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy

/**
 * ExoPlayer 是接口，用动态代理手写 fake（避免引 mock 框架）。
 * 记录服务/Provider 代码会调用的全部方法，其余返回类型默认值。
 *
 * 可配行为：
 * - [prepareThrows]：true 时 prepare() 抛异常，用于测试铃声源加载失败路径
 *   （AlertSoundProvider 的 setupUri/setupBuiltin catch → fallback 链）
 */
class FakeExoPlayer {
    var audioAttrsSet = false
    var prepared = false
    var mediaSet = false
    var lastMediaUri: String? = null
    val allMediaUris = mutableListOf<String?>()
    var repeatMode = -1
    var playWhenReady = false
    var stopped = false
    var released = false

    /** 全量失败开关：true 时 prepare 恒抛异常（全链失败场景） */
    var prepareThrows = false

    /**
     * 按 URI 精细控制失败：setMediaItem 之后 prepare 之前评估，
     * 返回 true 则 prepare 抛异常。用于「file 坏但 builtin 好」这类场景。
     */
    var prepareShouldFail: (String?) -> Boolean = { false }

    val player: ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "setAudioAttributes" -> { audioAttrsSet = true; null }
            "prepare" -> {
                if (prepareThrows || prepareShouldFail(lastMediaUri)) {
                    throw IllegalStateException("simulated prepare failure for $lastMediaUri")
                }
                prepared = true
                null
            }
            "setMediaItem" -> {
                mediaSet = true
                lastMediaUri = (args[0] as? androidx.media3.common.MediaItem)
                    ?.localConfiguration?.uri?.toString()
                allMediaUris.add(lastMediaUri)
                null
            }
            "setRepeatMode" -> { repeatMode = args[0] as Int; null }
            "setPlayWhenReady" -> { playWhenReady = args[0] as Boolean; null }
            "isPlaying" -> playWhenReady && prepared && !stopped && !released
            "stop" -> { stopped = true; null }
            "release" -> { released = true; stopped = true; null }
            "toString" -> "FakeExoPlayer"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            else -> defaultValue(method.returnType)
        }
    } as ExoPlayer

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '0'
        else -> null
    }

    companion object {
        /** 快速构造一个 fake player 实例（用于 playerFactory 注入） */
        fun newFactory(fakes: MutableList<FakeExoPlayer>): (android.content.Context) -> ExoPlayer = {
            FakeExoPlayer().also { fakes.add(it) }.player
        }
    }
}
