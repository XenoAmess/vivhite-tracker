package com.bilibili.livemonitor

import android.Manifest
import android.app.AlarmManager
import android.content.Context
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
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.OemHelper
import com.bilibili.livemonitor.util.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager

    // 本地状态标志，用于立即更新UI
    private var isServiceStarting = false
    private var isServiceStopping = false

    // 标记本次会话是否已弹过权限引导，避免重复打扰
    private var hasPromptedExactAlarm = false
    private var hasPromptedOem = false

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
        checkExactAlarmPermission()
        checkOemRestrictions()
    }

    override fun onResume() {
        super.onResume()
        // 重置过渡状态，从Service获取真实状态
        isServiceStarting = false
        isServiceStopping = false
        updateUI()
        // B站 App 安装状态可能变化，刷新打开直播间按钮着色
        updateOpenLiveButton()
        // 从设置页返回时复查精确闹钟权限（用户可能刚授权或被系统收回）
        if (!hasPromptedExactAlarm) {
            checkExactAlarmPermission()
        }
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

            btnOemSettings.setOnClickListener {
                OemHelper.openOemSettings(this@MainActivity)
            }

            btnViewLog.setOnClickListener {
                startActivity(Intent(this@MainActivity, LogActivity::class.java))
            }

            btnOpenLive.setOnClickListener {
                openLiveRoom()
            }

            btnOpenGithub.setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                )
            }

            btnInfo.setOnClickListener {
                showInfoDialog()
            }
        }

        updateOpenLiveButton()
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})"
    }

    // B站 App 可解析时醒目绿，否则灰色（两种状态都可点击，灰色走浏览器）
    internal fun isBilibiliAppAvailable(): Boolean {
        return liveRoomAppIntent().resolveActivity(packageManager) != null
    }

    internal fun liveRoomAppIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://live/$ROOM_ID"))
    }

    internal fun liveRoomWebIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/$ROOM_ID"))
    }

    private fun updateOpenLiveButton() {
        val colorRes = if (isBilibiliAppAvailable()) R.color.green_500 else android.R.color.darker_gray
        // 用 backgroundTintList 而不是 setBackgroundColor：MaterialButton 的
        // setBackgroundColor 走 helper 不改 tintList，语义不一致
        binding.btnOpenLive.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun openLiveRoom() {
        // 进直播间看就不再需要监控提醒：停止监控（铃声经 STOP→onDestroy 链路停止）
        if (LiveCheckService.isRunning) {
            stopMonitoring()
        }
        val intent = if (isBilibiliAppAvailable()) liveRoomAppIntent() else liveRoomWebIntent()
        try {
            startActivity(intent)
            Toast.makeText(this, "已停止监控，正在打开直播间", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // 极端情况：scheme 宣称可解析但启动失败，兜底浏览器
            startActivity(liveRoomWebIntent())
        }
        updateUI()
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("功能说明")
            .setMessage(
                "• 每分钟检查直播间状态\n" +
                    "• 开播时响铃+震动+屏幕提醒\n" +
                    "• 保持后台运行需关闭电池优化\n" +
                    "• 通知栏显示当前直播状态"
            )
            .setPositiveButton("知道了", null)
            .show()
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

            // 显示上次检测信息
            val lastTime = preferenceManager.getLastCheckTime()
            if (lastTime > 0) {
                val timeStr = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastTime))
                val resultStr = when {
                    !preferenceManager.isLastCheckSuccess() -> "检测失败"
                    preferenceManager.isLastCheckLive() -> "🔴 直播中"
                    else -> "⚫ 未开播"
                }
                tvLastCheck.text = "上次检测: $timeStr ($resultStr)"
            } else {
                tvLastCheck.text = "上次检测: 暂无记录"
            }
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
        preferenceManager.saveRoomId(ROOM_ID)
        preferenceManager.setServiceRunning(true)

        val serviceIntent = Intent(this, LiveCheckService::class.java).apply {
            putExtra(LiveCheckService.EXTRA_ROOM_ID, ROOM_ID)
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

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                hasPromptedExactAlarm = true
                AppLogger.w("MainActivity", "exact alarm permission not granted")
                AlertDialog.Builder(this)
                    .setTitle("需要精确闹钟权限")
                    .setMessage("没有精确闹钟权限时，黑屏待机状态下检测会被系统延迟到 15 分钟一次，容易漏掉开播提醒。请授予该权限以保证每分钟检测。")
                    .setPositiveButton("去开启") { _, _ ->
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                            )
                        } catch (e: Exception) {
                            AppLogger.e("MainActivity", "open exact alarm settings failed", e)
                            openAppDetails()
                        }
                    }
                    .setNegativeButton("稍后") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun checkOemRestrictions() {
        val oemInfo = OemHelper.getOemInfo() ?: return
        if (hasPromptedOem) return
        hasPromptedOem = true
        AppLogger.d("MainActivity", "detected OEM: ${oemInfo.displayName}")
        AlertDialog.Builder(this)
            .setTitle("${oemInfo.displayName} 后台保活设置")
            .setMessage(oemInfo.guideText)
            .setPositiveButton("去设置") { _, _ ->
                OemHelper.openOemSettings(this)
            }
            .setNegativeButton("稍后") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(appSettings)
    }

    companion object {
        private const val ROOM_ID = 11258892L
        private const val GITHUB_URL = "https://github.com/XenoAmess/vivhite-tracker"
    }
}
