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
import com.bilibili.livemonitor.api.BilibiliApi
import com.bilibili.livemonitor.api.UpdateChecker
import com.bilibili.livemonitor.domain.UpdateDecider
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.AppUpdater
import com.bilibili.livemonitor.util.OemHelper
import com.bilibili.livemonitor.util.QqGroups
import com.bilibili.livemonitor.util.QqShare
import com.bilibili.livemonitor.util.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        autoCheckUpdateIfDue()
    }

    override fun onResume() {
        super.onResume()
        // 重置过渡状态，从Service获取真实状态
        isServiceStarting = false
        isServiceStopping = false
        updateUI()
        // B站 App 安装状态可能变化，刷新打开直播间按钮着色
        updateOpenLiveButton()
        // 每次回到主页换一条名言
        refreshQuote()
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

            btnBackgroundSettings.setOnClickListener {
                openBackgroundSettings()
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

            btnShare.setOnClickListener {
                shareLiveRoom()
            }

            btnCheckUpdate.setOnClickListener {
                checkForUpdate(manual = true)
            }

            btnUpdateSettings.setOnClickListener {
                showUpdateSettingsDialog()
            }
        }

        setupQqGroups()
        updateOpenLiveButton()
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})"
    }

    // 应用内更新：检查源为 GitHub Releases（version.json 优先，APK 文件名兜底）
    internal var updateChecker: UpdateChecker = UpdateChecker()

    private val updateScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    // 前台每日一次静默自动检测：到点先落时间戳（失败也节流，避免每次进 App 都打 API）
    internal fun autoCheckUpdateIfDue(now: Long = System.currentTimeMillis()) {
        if (!preferenceManager.isAutoCheckUpdate()) return
        if (now - preferenceManager.getLastUpdateCheckTime() < UPDATE_CHECK_INTERVAL_MS) return
        preferenceManager.setLastUpdateCheckTime(now)
        checkForUpdate(manual = false)
    }

    internal fun checkForUpdate(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        }
        updateScope.launch {
            when (val state = updateChecker.checkLatestRelease(BuildConfig.VERSION_CODE)) {
                is UpdateDecider.UpdateState.UpdateAvailable -> {
                    val info = state.info
                    val dismissed = !manual && info.versionCode == preferenceManager.getDismissedVersionCode()
                    if (dismissed) return@launch
                    if (!manual && preferenceManager.isAutoDownloadUpdate() && AppUpdater.isOnWifi(this@MainActivity)) {
                        startUpdateDownload(info)
                    } else {
                        showUpdateDialog(info)
                    }
                }
                UpdateDecider.UpdateState.UpToDate -> {
                    if (manual) Toast.makeText(this@MainActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
                is UpdateDecider.UpdateState.Error -> {
                    AppLogger.w("MainActivity", "update check failed: ${state.reason}")
                    if (manual) showUpdateErrorDialog(state.reason)
                }
            }
        }
    }

    // 检查失败不再只有一个 Toast：区分网络错误/发布页格式，给出 Releases 页出口
    internal fun showUpdateErrorDialog(reason: String) {
        val message = if (reason == "network error") {
            "无法连接 GitHub，请检查网络后重试"
        } else {
            "暂时无法获取更新信息（$reason）"
        }
        AlertDialog.Builder(this)
            .setTitle("检查更新失败")
            .setMessage(message)
            .setPositiveButton("打开发布页") { _, _ ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("$GITHUB_URL/releases/latest"))
                    )
                } catch (e: Exception) {
                    AppLogger.e("MainActivity", "open releases page failed", e)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    internal fun showUpdateDialog(info: UpdateDecider.ReleaseInfo) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${info.versionName}")
            .setMessage(info.changelog.trim().ifBlank { "暂无更新说明" }.take(500))
            .setPositiveButton("立即更新") { _, _ -> startUpdateDownload(info) }
            .setNeutralButton("忽略此版本") { _, _ ->
                preferenceManager.setDismissedVersionCode(info.versionCode)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    internal fun startUpdateDownload(info: UpdateDecider.ReleaseInfo) {
        if (!AppUpdater.canRequestInstalls(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("系统要求授予「安装未知应用」权限后才能更新。开启后请重新点击检查更新。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        startActivity(AppUpdater.unknownSourcesIntent(this))
                    } catch (e: Exception) {
                        AppLogger.e("MainActivity", "open unknown sources settings failed", e)
                        openAppDetails()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val bar = dialogView.findViewById<android.widget.ProgressBar>(R.id.updateProgressBar)
        val label = dialogView.findViewById<android.widget.TextView>(R.id.tvUpdateProgressLabel)
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在下载更新 v${info.versionName}")
            .setView(dialogView)
            .setCancelable(false)
            .show()
        val dest = AppUpdater.apkFile(this, info.versionName)
        updateScope.launch {
            val ok = updateChecker.downloadApk(info.apkUrl, dest) { percent ->
                bar.post {
                    bar.progress = percent
                    label.text = "$percent%"
                }
            }
            dialog.dismiss()
            if (ok) {
                try {
                    startActivity(AppUpdater.buildInstallIntent(this@MainActivity, dest))
                } catch (e: Exception) {
                    AppLogger.e("MainActivity", "launch apk installer failed", e)
                    Toast.makeText(this@MainActivity, "无法打开安装器", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@MainActivity, "更新包下载失败，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun showUpdateSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_update_settings, null)
        val switchAutoCheck = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoCheck)
        val switchAutoDownload = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoDownload)
        switchAutoCheck.isChecked = preferenceManager.isAutoCheckUpdate()
        switchAutoDownload.isChecked = preferenceManager.isAutoDownloadUpdate()
        switchAutoCheck.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoCheckUpdate(isChecked)
        }
        switchAutoDownload.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoDownloadUpdate(isChecked)
        }
        AlertDialog.Builder(this)
            .setTitle("更新设置")
            .setView(view)
            .setPositiveButton("完成", null)
            .show()
    }

    // 三个 QQ 交流群项：头像+群名；装 QQ 拉起群资料卡，未装弹群号+复制
    private fun setupQqGroups() {
        val row = binding.qqGroupRow
        QqGroups.groups.forEach { group ->
            val item = layoutInflater.inflate(R.layout.item_qq_group, row, false)
            item.layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            item.findViewById<android.widget.ImageView>(R.id.ivQqAvatar)
                .setImageResource(group.avatarRes)
            item.findViewById<android.widget.TextView>(R.id.tvQqName).text = group.displayName
            item.setOnClickListener { openQqGroup(group) }
            row.addView(item)
        }
    }

    internal fun openQqGroup(group: QqGroups.QqGroup) {
        val qqPackage = OemHelper.installedQqPackage(packageManager)
        AppLogger.d("MainActivity", "openQqGroup ${group.number} qq detected=${qqPackage ?: "none"}")
        if (qqPackage != null) {
            try {
                startActivity(QqGroups.groupCardIntent(group, qqPackage))
            } catch (e: Exception) {
                AppLogger.w("MainActivity", "open qq group failed", e)
                showQqNumberDialog(group)
            }
        } else {
            showQqNumberDialog(group)
        }
    }

    // 未装 QQ（或拉起失败）时展示群号，一键复制后用户可自行搜索加入
    // 分享直播间：QQ 互联官方 SDK 真卡片（标题+封面+来源），失败兜底系统分享。
    // 链接按 B 站原生规则带 bbid 归因到指定用户。
    internal var qqSdkSharer: com.bilibili.livemonitor.util.QqSdkSharer =
        com.bilibili.livemonitor.util.DefaultQqSdkSharer()

    internal fun shareLiveRoom() {
        Toast.makeText(this, "正在生成分享卡片…", Toast.LENGTH_SHORT).show()
        shareScope.launch {
            val cover = withTimeoutOrNull(3000) {
                BilibiliApi().fetchRoomCover(QqShare.ROOM_ID)
            } ?: QqShare.FALLBACK_COVER_URL
            AppLogger.d("MainActivity", "share cover=$cover")
            try {
                qqSdkSharer.shareToQQ(this@MainActivity, QqShare.buildSdkShareParams(cover))
                AppLogger.d("MainActivity", "qq sdk shareToQQ invoked")
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "qq sdk share failed, fallback to system share", e)
                startActivity(Intent.createChooser(QqShare.buildSystemShareIntent(), "分享直播间"))
            }
        }
    }

    // 最终兜底：把带 bbid 归因的分享链接复制到剪贴板
    internal fun copyShareLinkToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("bilibili_live", QqShare.buildShareUrl())
        )
        Toast.makeText(this, "链接已复制到剪贴板", Toast.LENGTH_LONG).show()
    }

    private val shareScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    private fun showQqNumberDialog(group: QqGroups.QqGroup) {
        AlertDialog.Builder(this)
            .setTitle(group.displayName)
            .setMessage("未检测到 QQ 客户端。\n\n群号：${group.number}\n\n复制后打开 QQ 搜索群号即可加入。")
            .setPositiveButton("复制群号") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("qq_group", group.number)
                )
                Toast.makeText(this, "群号已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // B站 App 已安装（按包名检测）时醒目绿，否则灰色（两种状态都可点击）
    internal fun isBilibiliAppAvailable(): Boolean {
        return OemHelper.installedBilibiliVariants(packageManager).isNotEmpty()
    }

    internal fun liveRoomAppIntent(pkg: String?): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://live/$ROOM_ID")).apply {
            // setPackage 强制投递给指定客户端，绕开 resolveActivity 的
            // 包可见性不确定性（荣耀真机实测已装 bilibili 但解析为 null）
            if (!pkg.isNullOrEmpty()) setPackage(pkg)
        }
    }

    internal fun liveRoomWebIntent(pkg: String? = null): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/$ROOM_ID")).apply {
            // 用户显式选择某个浏览器时强制用它打开；空包名=系统自选
            if (!pkg.isNullOrEmpty()) setPackage(pkg)
        }
    }

    private fun updateOpenLiveButton() {
        val colorRes = if (isBilibiliAppAvailable()) R.color.green_500 else android.R.color.darker_gray
        // 用 backgroundTintList 而不是 setBackgroundColor：MaterialButton 的
        // setBackgroundColor 走 helper 不改 tintList，语义不一致
        binding.btnOpenLive.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun openLiveRoom() {
        // 汇总可选项：全部已装 bilibili 客户端（前）+ 全部浏览器（后）。
        // 装了客户端但没探测到浏览器时，补一个通用"浏览器"项保证网页路径可达
        val variants = OemHelper.installedBilibiliVariants(packageManager)
        val detectedBrowsers = OemHelper.installedBrowsers(packageManager, liveRoomWebIntent(), packageName)
        val browsers = detectedBrowsers.ifEmpty {
            listOf(OemHelper.BilibiliVariant("", "浏览器"))
        }
        AppLogger.d(
            "MainActivity",
            "openLiveRoom bilibili=${variants.map { it.packageName }} browsers=${browsers.map { it.packageName }}"
        )
        val targets = variants.map { it to false } + browsers.map { it to true }

        when (targets.size) {
            0 -> jumpToLiveRoom(null, true) // 理论不可能，兜底普通 https
            1 -> jumpToLiveRoom(targets[0].first, targets[0].second) // 仅一个选项，直跳不弹框
            else -> showLiveRoomChooser(targets)
        }
    }

    private fun showLiveRoomChooser(targets: List<Pair<OemHelper.BilibiliVariant, Boolean>>) {
        val labels = targets.map { it.first.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("打开直播间")
            .setItems(labels) { dialog, which ->
                val (target, isWeb) = targets[which]
                jumpToLiveRoom(target, isWeb)
                dialog.dismiss()
            }
            .show()
    }

    // isWeb=false 表示 bilibili 客户端（bilibili:// scheme + setPackage），
    // isWeb=true 表示浏览器（https + setPackage，包名为空时系统自选）
    private fun jumpToLiveRoom(target: OemHelper.BilibiliVariant?, isWeb: Boolean) {
        // 观播静音：监控不停，本场直播结束前不提醒（正在响的铃立即停）。
        // 仅在监控中且当前在播时才置静音；其余情况只跳转
        if (LiveCheckService.isRunning && LiveCheckService.lastLiveStatus) {
            preferenceManager.setAlertSuppressed(true)
            val muteIntent = Intent(this, LiveCheckService::class.java).apply {
                action = LiveCheckService.ACTION_WATCH_LIVE
            }
            startService(muteIntent)
            Toast.makeText(this, "已静音观播，下播后恢复提醒", Toast.LENGTH_SHORT).show()
        }
        val intent = if (isWeb) {
            liveRoomWebIntent(target?.packageName)
        } else {
            liveRoomAppIntent(target?.packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 极端情况：scheme 宣称可解析但启动失败，兜底不带包的 https
            AppLogger.w("MainActivity", "start failed, fallback to plain https", e)
            startActivity(liveRoomWebIntent())
        }
        updateUI()
    }

    private fun updateUI() {
        // 结合Service状态和本地过渡状态来确定UI显示
        val isRunning = when {
            isServiceStarting -> true  // 正在启动，显示为运行中
            isServiceStopping -> false // 正在停止，显示为已停止
            else -> LiveCheckService.isRunning
        }

        binding.apply {
            val muted = isRunning && preferenceManager.isAlertSuppressed()
            tvStatus.text = when {
                muted -> "监控状态: 运行中（本场静音）"
                isRunning -> "监控状态: 运行中"
                else -> "监控状态: 已停止"
            }
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
        // 国产 ROM 的首启引导由 checkOemRestrictions 的厂商弹窗统一负责，
        // 避免连弹三个对话框；且华为/荣耀上标准电池优化 intent 无效，
        // 弹了也是把用户带到死路（荣耀真机实测点了毫无反应）
        if (OemHelper.isProblematicOem()) return
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
        // 厂商自启动设置状态无 API 可读，弹过一次就持久化跳过，
        // 否则每次冷启动都会重复弹（用户反馈：明明设置过了还弹）
        if (hasPromptedOem || preferenceManager.isOemGuidePrompted()) return
        hasPromptedOem = true
        preferenceManager.setOemGuidePrompted(true)
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

    // 「后台运行设置」统一入口：按厂商路由。
    // 国产 ROM（含标准电池优化 intent 无效的华为/荣耀）优先厂商自启动页；
    // 原生 Android 走标准电池优化白名单。
    internal fun openBackgroundSettings() {
        val oemInfo = OemHelper.getOemInfo()
        if (oemInfo == null) {
            openBatterySettings()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${oemInfo.displayName} 后台保活设置")
            .setMessage(oemInfo.guideText)
            .setPositiveButton("去厂商设置") { _, _ ->
                OemHelper.openOemSettings(this)
            }
            .apply {
                // 华为/荣耀的标准 intent 是死路，不展示该选项；
                // 其他国产 ROM（小米/一加等）作为补充入口保留
                if (OemHelper.standardBatteryIntentWorksHere()) {
                    setNeutralButton("电池优化设置") { _, _ ->
                        openBatterySettings()
                    }
                }
            }
            .setNegativeButton("取消", null)
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
            // 部分小众 ROM 同样空转该 intent：解析不到直接降级应用详情页
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                AppLogger.w("MainActivity", "battery optimization intent not resolvable, fallback to app details")
                openAppDetails()
            }
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

    // 每次回到主页随机展示一条名言（首启必出邓煜，1/10 概率白绮，其余常规池防重复）
    internal fun refreshQuote() {
        val quote = com.bilibili.livemonitor.domain.QuotePicker.pick(
            isFirstLaunchDone = preferenceManager.isFirstLaunchDone(),
            lastIndex = lastQuoteIndex
        )
        if (!preferenceManager.isFirstLaunchDone()) {
            preferenceManager.setFirstLaunchDone(true)
        }
        lastQuoteIndex = com.bilibili.livemonitor.domain.QuotePicker.poolIndexOf(quote)
        binding.tvQuote.text = "「${quote.text}」—— ${quote.author}"
    }

    companion object {
        private const val ROOM_ID = 11258892L
        private const val GITHUB_URL = "https://github.com/XenoAmess/vivhite-tracker"

        // 自动检查更新的最小间隔：24 小时
        internal const val UPDATE_CHECK_INTERVAL_MS = 24 * 3600 * 1000L

        // 上次展示的常规池下标，防连续重复（静态保存，跨 Activity 实例有效）
        private var lastQuoteIndex: Int? = null
    }
}
