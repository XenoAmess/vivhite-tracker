package com.bilibili.livemonitor

import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bilibili.livemonitor.databinding.ActivityAlertBinding
import kotlinx.coroutines.*

class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private var mediaPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置窗口属性，确保能在锁屏时显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 唤醒屏幕
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "BilibiliLiveMonitor::AlertWakeLock"
        )
        wakeLock.acquire(30 * 1000L)

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 拦截返回手势/返回键，强制用户点击按钮（targetSdk 36+ 需用 OnBackPressedDispatcher）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 不处理返回，强制用户点击按钮
            }
        })

        setupUI()
        playAlarm()

        // 30秒后自动关闭
        scope.launch {
            delay(30000)
            finish()
        }

        // 释放唤醒锁
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun setupUI() {
        binding.apply {
            tvAlertTitle.text = "🎉 白绮开播啦！"
            tvAlertMessage.text = "直播间 11258892 正在直播中\n快去看看吧！"
            
            btnGoToLive.setOnClickListener {
                // 打开直播间
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("https://live.bilibili.com/11258892")
                }
                startActivity(intent)
                finish()
            }

            btnDismiss.setOnClickListener {
                finish()
            }
        }
    }

    private val alertSoundProvider = com.bilibili.livemonitor.util.AlertSoundProvider()

    private fun playAlarm() {
        try {
            val prefs = com.bilibili.livemonitor.util.PreferenceManager(this)
            mediaPlayer = ExoPlayer.Builder(this).build().apply {
                val attrs = Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_ALARM)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                    .build()
                setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                if (!alertSoundProvider.setupDataSource(
                        this@AlertActivity, this, prefs.getAlertSoundUri()
                    )) {
                    com.bilibili.livemonitor.util.AppLogger.w("AlertActivity", "all sound sources failed, skip alarm")
                    release()
                    mediaPlayer = null
                    return@apply
                }
                repeatMode = Player.REPEAT_MODE_ONE  // gapless 循环
                playWhenReady = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        scope.cancel()
    }
}
