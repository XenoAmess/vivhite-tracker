package com.bilibili.livemonitor

import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.bilibili.livemonitor.databinding.ActivityAlertBinding
import com.bilibili.livemonitor.service.LiveCheckService
import kotlinx.coroutines.*

class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        val root = binding.root
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom
            )
            insets
        }

        // 返回键与页面按钮语义一致：立即停铃并关闭，不把用户困在全屏提醒中。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismissAlert()
            }
        })

        setupUI()
        // 声音由 LiveCheckService 唯一持有；此页只承接通知的全屏展示，避免叠音。

        // 30秒后自动关闭
        scope.launch {
            delay(30000)
            dismissAlert()
        }

        // 释放唤醒锁
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun setupUI() {
        binding.apply {
            tvAlertTitle.text = "🎉 白绮开播啦！"
            tvAlertMessage.text = "直播间 ${com.bilibili.livemonitor.util.BiliTargets.ROOM_ID} 正在直播中\n快去看看吧！"

            btnGoToLive.setOnClickListener {
                notifyLiveService(LiveCheckService.ACTION_WATCH_LIVE)
                // 打开直播间
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("https://live.bilibili.com/${com.bilibili.livemonitor.util.BiliTargets.ROOM_ID}")
                }
                startActivity(intent)
                finish()
            }

            btnDismiss.setOnClickListener {
                dismissAlert()
            }
        }
    }

    private fun dismissAlert() {
        notifyLiveService(LiveCheckService.ACTION_STOP_ALERT)
        finish()
    }

    private fun notifyLiveService(action: String) {
        startService(android.content.Intent(this, LiveCheckService::class.java).setAction(action))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
