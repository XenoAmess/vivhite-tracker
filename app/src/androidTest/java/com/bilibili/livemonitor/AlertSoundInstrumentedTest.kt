package com.bilibili.livemonitor

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.util.AlertSoundProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 提醒铃声主线程修复的真机/模拟器验证。
 *
 * 背景：LiveCheckService.playAlertSound 曾在 Dispatchers.IO 上创建
 * ExoPlayer，media3 的 verifyApplicationThread 抛 wrong-thread
 * IllegalStateException，被 catch 静默吞掉 → 感知到开播但完全无声。
 * 修复后统一走 mainDispatcher（=Dispatchers.Main）。
 *
 * 单测（Robolectric）只能证明"经过了 mainDispatcher"（ExoPlayer 无法构造），
 * 本测试在真 Android 框架上证明链路的另一半：
 * 主线程 + 真 ExoPlayer + 真 AlertSoundProvider → 真实解码播放内置铃声。
 */
@RunWith(AndroidJUnit4::class)
class AlertSoundInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var player: ExoPlayer? = null

    @After
    fun tearDown() {
        player?.let { p ->
            instrumentation.runOnMainSync {
                runCatching { p.stop() }
                runCatching { p.release() }
            }
        }
        player = null
    }

    @Test
    fun 主线程创建ExoPlayer加载内置默认铃声并真实播放() {
        // 与修复后 LiveCheckService.playAlertSound 完全同构的调用序列
        val latch = CountDownLatch(1)
        var createError: Throwable? = null

        instrumentation.runOnMainSync {
            try {
                player = ExoPlayer.Builder(context).build().apply {
                    val attrs = androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_ALARM)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                        .build()
                    setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                    val ok = AlertSoundProvider().setupDataSource(
                        context, this, /* uriPref = */ "" // 空 → 内置默认 海愿
                    )
                    assertTrue("内置默认铃声必须加载成功", ok)
                    repeatMode = Player.REPEAT_MODE_ONE
                    playWhenReady = true
                }
            } catch (t: Throwable) {
                createError = t
            }
            latch.countDown()
        }

        assertTrue("主线程执行超时", latch.await(10, TimeUnit.SECONDS))
        assertNull("主线程创建 ExoPlayer 不得抛异常（修复前的 wrong-thread bug）", createError)
        assertNotNull(player)

        // 等真实解码起播（emulator 软解码 mp3 需要一点时间）
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var playing = false
            var state = Player.STATE_IDLE
            instrumentation.runOnMainSync {
                playing = player?.isPlaying == true
                state = player?.playbackState ?: Player.STATE_IDLE
            }
            if (playing && state == Player.STATE_READY) return
            Thread.sleep(200)
        }
        var finalPlaying = false
        var finalState = Player.STATE_IDLE
        instrumentation.runOnMainSync {
            finalPlaying = player?.isPlaying == true
            finalState = player?.playbackState ?: Player.STATE_IDLE
        }
        assertTrue(
            "铃声必须真实起播（isPlaying=$finalPlaying, state=$finalState）",
            finalPlaying && finalState == Player.STATE_READY
        )
    }

    @Test
    fun 后台线程直接创建ExoPlayer会抛wrongthread_反证修复必要性() {
        // 反证：同样的调用放在无 Looper 的后台线程上，media3 必须抛异常。
        // 这验证了"修复前的写法在真机上必炸"这一诊断，防止有人把代码改回去。
        val latch = CountDownLatch(1)
        var wrongThreadError: Throwable? = null

        Thread {
            try {
                val p = ExoPlayer.Builder(context).build()
                p.setMediaItem(MediaItem.fromUri("android.resource://${context.packageName}/${R.raw.alert_1}"))
                p.prepare()
                p.release()
            } catch (t: Throwable) {
                wrongThreadError = t
            }
            latch.countDown()
        }.start()

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertNotNull(
            "无 Looper 线程创建/操作 ExoPlayer 应抛 wrong-thread 异常（证明修复必要）",
            wrongThreadError
        )
    }
}
