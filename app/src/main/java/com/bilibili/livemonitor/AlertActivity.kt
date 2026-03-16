package com.bilibili.livemonitor

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.bilibili.livemonitor.databinding.ActivityAlertBinding
import kotlinx.coroutines.*

class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private var mediaPlayer: MediaPlayer? = null
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

    private fun playAlarm() {
        try {
            val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlertActivity, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
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

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // 不处理返回键，强制用户点击按钮
    }
}
