package com.bilibili.livemonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bilibili.livemonitor.databinding.ActivityMainBinding
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager

    // 本地状态标志，用于立即更新UI
    private var isServiceStarting = false
    private var isServiceStopping = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startMonitoring()
        } else {
            Snackbar.make(
                binding.root,
                "需要通知权限才能正常运行",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)

        // 如果之前用户在监控，但服务被系统杀掉了，重新打开App时自动恢复
        if (preferenceManager.isServiceRunning() && !LiveCheckService.isRunning) {
            startMonitoring()
        }

        setupUI()
        checkBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        // 重置过渡状态，从Service获取真实状态
        isServiceStarting = false
        isServiceStopping = false
        updateUI()
    }

    private fun setupUI() {
        binding.apply {
            btnToggle.setOnClickListener {
                // 防止重复点击
                if (isServiceStarting || isServiceStopping) {
                    return@setOnClickListener
                }

                if (LiveCheckService.isRunning) {
                    isServiceStopping = true
                    updateUI() // 立即更新UI
                    stopMonitoring()
                } else {
                    if (checkNotificationPermission()) {
                        isServiceStarting = true
                        updateUI() // 立即更新UI
                        startMonitoring()
                    }
                }
            }

            btnOpenSettings.setOnClickListener {
                openBatterySettings()
            }
        }
    }

    private fun updateUI() {
        // 结合Service状态和本地过渡状态来确定UI显示
        val isRunning = when {
            isServiceStarting -> true  // 正在启动，显示为运行中
            isServiceStopping -> false // 正在停止，显示为已停止
            else -> LiveCheckService.isRunning
        }

        binding.apply {
            tvStatus.text = if (isRunning) "监控状态: 运行中" else "监控状态: 已停止"
            tvStatus.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (isRunning) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )

            // 根据状态切换按钮文本和颜色
            if (isRunning) {
                btnToggle.text = "停止监控"
                btnToggle.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.red_500))
                btnToggle.setIconResource(android.R.drawable.ic_media_pause)
            } else {
                btnToggle.text = "开始监控"
                btnToggle.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.green_500))
                btnToggle.setIconResource(android.R.drawable.ic_media_play)
            }

            // 更新图标
            val iconRes = if (isRunning && LiveCheckService.lastLiveStatus) {
                R.drawable.img_on
            } else {
                R.drawable.img_off
            }
            ivStatus.setImageResource(iconRes)
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> true
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    AlertDialog.Builder(this)
                        .setTitle("需要通知权限")
                        .setMessage("应用需要在通知栏显示以保持后台运行，请授予通知权限")
                        .setPositiveButton("确定") { _, _ ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    false
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    false
                }
            }
        } else {
            true
        }
    }

    private fun startMonitoring() {
        val roomId = 11258892L
        preferenceManager.saveRoomId(roomId)
        preferenceManager.setServiceRunning(true)

        val serviceIntent = Intent(this, LiveCheckService::class.java).apply {
            putExtra(LiveCheckService.EXTRA_ROOM_ID, roomId)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(this, "已开始监控直播间 11258892", Toast.LENGTH_SHORT).show()

        // 使用延迟来确保Service有足够时间启动，然后清除过渡状态
        binding.root.postDelayed({
            isServiceStarting = false
            updateUI()
        }, 500)
    }

    private fun stopMonitoring() {
        preferenceManager.setServiceRunning(false)
        // 发送停止命令，让服务自己停止（避免自动重启）
        val stopIntent = Intent(this, LiveCheckService::class.java).apply {
            action = LiveCheckService.ACTION_STOP_SERVICE
        }
        startService(stopIntent)

        Toast.makeText(this, "已停止监控", Toast.LENGTH_SHORT).show()

        // 使用延迟来确保Service有足够时间停止，然后清除过渡状态
        binding.root.postDelayed({
            isServiceStopping = false
            updateUI()
        }, 500)
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("电池优化提醒")
            .setMessage("为了保证应用能在后台正常运行，请将本应用添加到电池优化白名单中。")
            .setPositiveButton("去设置") { _, _ ->
                openBatterySettings()
            }
            .setNegativeButton("稍后") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun openBatterySettings() {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                else -> {
                    action = Settings.ACTION_SETTINGS
                }
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 如果打不开特定设置，打开应用详情
            val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(appSettings)
        }
    }
}
