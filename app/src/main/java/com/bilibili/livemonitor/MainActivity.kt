package com.bilibili.livemonitor

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.widget.TextView
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
import com.bilibili.livemonitor.util.AlertSoundProvider
import com.bilibili.livemonitor.util.BuiltInSound
import com.bilibili.livemonitor.util.OemHelper
import com.bilibili.livemonitor.util.QqGroups
import com.bilibili.livemonitor.util.QqShare
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.domain.AlertSoundDecider
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

    // 系统铃声库 picker：ACTION_RINGTONE_PICKER 返回的 uri 在 onActivityResult 里拿
    private val systemRingtoneLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                ?.let { uri ->
                    val title = resolveRingtoneTitle(uri)
                    val encoded = AlertSoundDecider.encodeSystem(uri.toString())
                    preferenceManager.setAlertSoundUri(encoded)
                    preferenceManager.setAlertSoundTitle(title)
                    AppLogger.d("MainActivity", "system ringtone picked: $uri ($title)")
                    Toast.makeText(this, "已设置铃声：$title", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // 音频文件 picker：SAF OPEN_DOCUMENT，返回的 uri 必须 takePersistableUriPermission
    // 否则进程被杀后读不出（这是 SAF 自定义铃声的最大坑）
    private val audioFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val title = resolveRingtoneTitle(uri)
                val encoded = AlertSoundDecider.encodeFile(uri.toString())
                preferenceManager.setAlertSoundUri(encoded)
                preferenceManager.setAlertSoundTitle(title)
                AppLogger.d("MainActivity", "audio file picked: $uri ($title)")
                Toast.makeText(this, "已设置铃声：$title", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                AppLogger.e("MainActivity", "takePersistableUriPermission failed", e)
                Toast.makeText(this, "无法获取该文件的长期访问权限，请换一个文件", Toast.LENGTH_LONG).show()
            }
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

    override fun onPause() {
        super.onPause()
        stopPreview()
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

            btnSettings.setOnClickListener {
                showSettingsDrawer()
            }

            btnViewLog.setOnClickListener {
                startActivity(Intent(this@MainActivity, LogActivity::class.java))
            }

            btnOpenLive.setOnClickListener {
                openLiveRoom()
            }

            btnOpenSpace.setOnClickListener {
                openSpace()
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

    // ========== 统一设置抽屉 ==========

    /**
     * 弹出 BottomSheet 抽屉，4 个设置项内嵌展开。
     * 同一时刻只有一个 Section 展开（互斥）。
     */
    internal fun showSettingsDrawer() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = layoutInflater.inflate(R.layout.dialog_settings_drawer, null)
        val container = root.findViewById<android.widget.LinearLayout>(R.id.itemsContainer)

        val entries = listOf(
            SettingsEntry(
                title = "后台保活设置",
                subtitle = "电池优化 / OEM 自启动引导",
                iconRes = android.R.drawable.ic_menu_manage,
                expandLayoutRes = R.layout.expand_section_maintenance,
                onExpand = { view -> bindMaintenanceSection(view) }
            ),
            SettingsEntry(
                title = "提醒铃声",
                subtitle = computeRingtoneSubtitle(),
                iconRes = android.R.drawable.ic_media_play,
                expandLayoutRes = R.layout.expand_section_ringtone,
                onExpand = { view -> bindRingtoneSection(view) }
            ),
            SettingsEntry(
                title = "活动监控",
                subtitle = computeActivitySubtitle(),
                iconRes = android.R.drawable.ic_menu_my_calendar,
                expandLayoutRes = R.layout.expand_section_activity,
                onExpand = { view -> bindActivitySection(view) }
            ),
            SettingsEntry(
                title = "更新设置",
                subtitle = computeUpdateSubtitle(),
                iconRes = android.R.drawable.ic_menu_manage,
                expandLayoutRes = R.layout.expand_section_update,
                onExpand = { view -> bindUpdateSection(view) }
            )
        )

        entries.forEach { entry ->
            val itemView = layoutInflater.inflate(R.layout.item_settings_drawer, container, false)
            container.addView(itemView)
            bindSettingsItem(itemView, entry)
        }

        sheet.setContentView(root)
        sheet.setOnDismissListener { stopPreview() }
        sheet.show()
    }

    private data class SettingsEntry(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val expandLayoutRes: Int,
        val onExpand: (android.view.View) -> Unit
    )

    // 互斥展开：同一时刻只有一个 Section 展开
    private var currentExpanded: android.view.ViewGroup? = null

    private fun bindSettingsItem(itemView: android.view.View, entry: SettingsEntry) {
        itemView.findViewById<android.widget.TextView>(R.id.tvTitle).text = entry.title
        itemView.findViewById<android.widget.TextView>(R.id.tvSubtitle).text = entry.subtitle
        itemView.findViewById<android.widget.ImageView>(R.id.ivIcon).setImageResource(entry.iconRes)
        val container = itemView.findViewById<android.widget.FrameLayout>(R.id.expandContainer)

        itemView.findViewById<android.view.View>(R.id.itemRoot).setOnClickListener {
            if (container.visibility == android.view.View.VISIBLE) {
                collapseSection(container)
            } else {
                currentExpanded?.let { collapseSection(it) }
                container.removeAllViews()
                val content = layoutInflater.inflate(entry.expandLayoutRes, container, false)
                entry.onExpand(content)
                container.addView(content)
                expandSection(container)
            }
        }
    }

    private fun expandSection(container: android.view.ViewGroup) {
        container.visibility = android.view.View.VISIBLE
        currentExpanded = container
    }

    private fun collapseSection(container: android.view.ViewGroup) {
        container.visibility = android.view.View.GONE
        container.removeAllViews()
        if (currentExpanded === container) currentExpanded = null
        stopPreview()
    }

    private fun computeRingtoneSubtitle(): String {
        val title = preferenceManager.getAlertSoundTitle()
        return if (title.isNotBlank()) "当前: $title" else "当前: 应用默认"
    }

    private fun computeActivitySubtitle(): String {
        var on = 0
        if (preferenceManager.isMonitorVideos()) on++
        if (preferenceManager.isMonitorPinned()) on++
        if (preferenceManager.isMonitorDynamics()) on++
        val ring = preferenceManager.isAlertRingOnActivity()
        val ringText = if (ring) " 响铃" else ""
        return "视频·置顶·动态 $on/3 已开$ringText"
    }

    private fun computeUpdateSubtitle(): String {
        val check = if (preferenceManager.isAutoCheckUpdate()) "开" else "关"
        val dl = if (preferenceManager.isAutoDownloadUpdate()) "开" else "关"
        return "自动检查: $check  自动下载: $dl"
    }

    private fun bindMaintenanceSection(view: android.view.View) {
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenBackgroundSettings)
            .setOnClickListener { openBackgroundSettings() }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenBatterySettings)
            .setOnClickListener { openBatterySettings() }
    }

    private fun bindRingtoneSection(view: android.view.View) {
        val container = view.findViewById<android.widget.LinearLayout>(R.id.builtinSoundsContainer)
        val currentUri = preferenceManager.getAlertSoundUri()
        val currentSource = AlertSoundDecider.resolve(currentUri)
        val radioButtons = mutableListOf<android.widget.RadioButton>()

        BuiltInSound.values().forEach { sound ->
            val item = layoutInflater.inflate(R.layout.item_builtin_sound, container, false)
            val rb = item.findViewById<android.widget.RadioButton>(R.id.rbSound)!!
            val tvName = item.findViewById<TextView>(R.id.tvSoundName)
            val btnPreview = item.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPreview)
            tvName.text = sound.title
            radioButtons.add(rb)
            val isSelected = when (currentSource) {
                is com.bilibili.livemonitor.domain.SoundSource.Default -> sound == BuiltInSound.DEFAULT
                is com.bilibili.livemonitor.domain.SoundSource.BuiltIn -> currentSource.key == sound.key
                else -> false
            }
            if (isSelected) rb.isChecked = true
            item.setOnClickListener {
                radioButtons.forEach { it.isChecked = false }
                rb.isChecked = true
                preferenceManager.setAlertSoundUri(AlertSoundDecider.encodeBuiltIn(sound.key))
                preferenceManager.setAlertSoundTitle(sound.title)
                AppLogger.d("MainActivity", "builtin sound selected: ${sound.key}")
            }
            btnPreview.setOnClickListener { previewSound(sound) }
            container.addView(item)
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickSystemRingtone)
            .setOnClickListener {
                stopPreview()
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                }
                systemRingtoneLauncher.launch(intent)
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickAudioFile)
            .setOnClickListener {
                stopPreview()
                audioFileLauncher.launch(arrayOf("audio/*"))
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRestoreDefault)
            .setOnClickListener {
                preferenceManager.setAlertSoundUri("")
                preferenceManager.setAlertSoundTitle("")
                AppLogger.d("MainActivity", "alert sound restored to default")
                Toast.makeText(this, "已恢复默认铃声", Toast.LENGTH_SHORT).show()
            }
    }

    private fun bindActivitySection(view: android.view.View) {
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorVideos).apply {
            isChecked = preferenceManager.isMonitorVideos()
            setOnCheckedChangeListener { _, c -> preferenceManager.setMonitorVideos(c) }
        }
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorPinned).apply {
            isChecked = preferenceManager.isMonitorPinned()
            setOnCheckedChangeListener { _, c -> preferenceManager.setMonitorPinned(c) }
        }
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorDynamics).apply {
            isChecked = preferenceManager.isMonitorDynamics()
            setOnCheckedChangeListener { _, c -> preferenceManager.setMonitorDynamics(c) }
        }
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAlertRingOnActivity).apply {
            isChecked = preferenceManager.isAlertRingOnActivity()
            setOnCheckedChangeListener { _, c -> preferenceManager.setAlertRingOnActivity(c) }
        }
    }

    private fun bindUpdateSection(view: android.view.View) {
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoCheck).apply {
            isChecked = preferenceManager.isAutoCheckUpdate()
            setOnCheckedChangeListener { _, c -> preferenceManager.setAutoCheckUpdate(c) }
        }
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoDownload).apply {
            isChecked = preferenceManager.isAutoDownloadUpdate()
            setOnCheckedChangeListener { _, c -> preferenceManager.setAutoDownloadUpdate(c) }
        }
    }

    internal fun checkForUpdate(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        }
        updateScope.launch {
            when (val state = updateChecker.checkLatestRelease(
                BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME
            )) {
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

    // ========== 活动监控设置 ==========

    internal fun showActivitySettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_activity_settings, null)
        val switchVideos = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorVideos)
        val switchPinned = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorPinned)
        val switchDynamics = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorDynamics)
        val switchRing = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAlertRingOnActivity)

        switchVideos.isChecked = preferenceManager.isMonitorVideos()
        switchPinned.isChecked = preferenceManager.isMonitorPinned()
        switchDynamics.isChecked = preferenceManager.isMonitorDynamics()
        switchRing.isChecked = preferenceManager.isAlertRingOnActivity()

        switchVideos.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setMonitorVideos(isChecked)
            AppLogger.d("MainActivity", "monitorVideos=$isChecked")
        }
        switchPinned.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setMonitorPinned(isChecked)
            AppLogger.d("MainActivity", "monitorPinned=$isChecked")
        }
        switchDynamics.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setMonitorDynamics(isChecked)
            AppLogger.d("MainActivity", "monitorDynamics=$isChecked")
        }
        switchRing.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAlertRingOnActivity(isChecked)
        }

        AlertDialog.Builder(this)
            .setTitle("活动监控")
            .setView(view)
            .setPositiveButton("完成", null)
            .show()
    }

    // ========== 提醒铃声自定义 ==========

    // 试听播放器（dialog dismiss 时必须释放，避免泄漏）
    private var previewPlayer: ExoPlayer? = null
    private val alertSoundProvider = AlertSoundProvider()
    // 当前分享用的直播标题（fetchRoomInfo 拿到，供 fallbackToSystemShare 使用）
    private var currentShareTitle: String? = null

    internal fun showAlertDialogSoundDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_alert_sound, null)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.builtinSoundsContainer)
        val currentUri = preferenceManager.getAlertSoundUri()

        // 手动管理单选（RadioGroup 只能管理直接子 RadioButton，
        // 但我们的 item 是 LinearLayout 包含 RadioButton，所以用手动方式）
        val radioButtons = mutableListOf<android.widget.RadioButton>()

        // 解析当前铃声源，判断该勾哪个内置项
        val currentSource = AlertSoundDecider.resolve(currentUri)

        // 填充内置铃声池
        BuiltInSound.values().forEach { sound ->
            val item = layoutInflater.inflate(R.layout.item_builtin_sound, container, false)
            val rb = item.findViewById<android.widget.RadioButton>(R.id.rbSound)!!
            val tvName = item.findViewById<TextView>(R.id.tvSoundName)
            val btnPreview = item.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPreview)

            tvName.text = sound.title
            radioButtons.add(rb)

            // 勾选当前生效的内置项：Default → CLASSIC_1，BuiltIn → 匹配 key 的项
            val isSelected = when (currentSource) {
                is com.bilibili.livemonitor.domain.SoundSource.Default -> sound == BuiltInSound.DEFAULT
                is com.bilibili.livemonitor.domain.SoundSource.BuiltIn -> currentSource.key == sound.key
                else -> false  // System/File 不勾任何内置项
            }
            if (isSelected) rb.isChecked = true

            // 点整行 = 选中这一项（先清其他，再勾自己）
            item.setOnClickListener {
                radioButtons.forEach { it.isChecked = false }
                rb.isChecked = true
                preferenceManager.setAlertSoundUri(AlertSoundDecider.encodeBuiltIn(sound.key))
                preferenceManager.setAlertSoundTitle(sound.title)
                AppLogger.d("MainActivity", "builtin sound selected: ${sound.key}")
            }

            // 点试听按钮 = 播 2 秒预览
            btnPreview.setOnClickListener {
                previewSound(sound)
            }

            container.addView(item)
        }

        // 如果当前是自定义（system/file），不勾任何内置项

        val dialog = AlertDialog.Builder(this)
            .setTitle("提醒铃声")
            .setView(view)
            .setPositiveButton("完成", null)
            .setOnDismissListener {
                stopPreview()
            }
            .show()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickSystemRingtone)
            .setOnClickListener {
                stopPreview()
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                }
                systemRingtoneLauncher.launch(intent)
                dialog.dismiss()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickAudioFile)
            .setOnClickListener {
                stopPreview()
                audioFileLauncher.launch(arrayOf("audio/*"))
                dialog.dismiss()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRestoreDefault)
            .setOnClickListener {
                preferenceManager.setAlertSoundUri("")
                preferenceManager.setAlertSoundTitle("")
                AppLogger.d("MainActivity", "alert sound restored to default")
                Toast.makeText(this, "已恢复默认铃声", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
    }

    // 试听内置铃声（ExoPlayer gapless 循环，停止由 stopPreview() 触发）
    private fun previewSound(sound: BuiltInSound) {
        stopPreview()
        try {
            previewPlayer = ExoPlayer.Builder(this).build().apply {
                val attrs = Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_ALARM)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                    .build()
                setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                if (!alertSoundProvider.setupDataSource(
                        this@MainActivity, this, AlertSoundDecider.encodeBuiltIn(sound.key)
                    )) {
                    release()
                    previewPlayer = null
                    return@apply
                }
                repeatMode = Player.REPEAT_MODE_ONE  // gapless 循环
                playWhenReady = true
            }
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "preview sound ${sound.key} failed", e)
        }
    }

    private fun stopPreview() {
        previewPlayer?.let { p ->
            try {
                if (p.isPlaying) p.stop()
                p.release()
            } catch (_: Exception) {}
        }
        previewPlayer = null
    }

    // 取铃声展示名：系统铃声库用 RingtoneManager 取标题，文件用 uri 最后一段
    private fun resolveRingtoneTitle(uri: Uri): String {
        return try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this)
                ?: uri.lastPathSegment ?: "自定义铃声"
        } catch (e: Exception) {
            AppLogger.w("MainActivity", "resolve ringtone title failed for $uri", e)
            uri.lastPathSegment ?: "自定义铃声"
        }
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
        // 注入 applicationContext 给 DefaultQqSdkSharer（isAuthorized/login 需要）
        (qqSdkSharer as? com.bilibili.livemonitor.util.DefaultQqSdkSharer)?.bind(applicationContext)
        shareScope.launch {
            val roomInfo = withTimeoutOrNull(3000) {
                BilibiliApi().fetchRoomInfo(QqShare.ROOM_ID)
            }
            val cover = roomInfo?.cover ?: QqShare.FALLBACK_COVER_URL
            val title = roomInfo?.title
            AppLogger.d("MainActivity", "share cover=$cover title=$title")
            currentShareTitle = title
            val params = QqShare.buildSdkShareParams(cover, title)
            doQqShare(params)
        }
    }

    private fun doQqShare(params: android.os.Bundle) {
        AppLogger.d("MainActivity", "doQqShare isAuthorized=${qqSdkSharer.isAuthorized()}")
        if (qqSdkSharer.isAuthorized()) {
            // 已授权：直接走真卡片
            doQqShareAfterAuthorized(params)
        } else {
            // 未授权：弹引导对话框让用户选「去授权」或「普通分享」
            showQqAuthGuideDialog(params)
        }
    }

    private fun showQqAuthGuideDialog(params: android.os.Bundle) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("QQ 分享需要先授权")
            .setMessage(
                "首次分享到 QQ 需要先在 QQ 端授权「牢白播了吗」使用 QQ 互联能力。\n\n" +
                "点「去 QQ 授权」完成授权后，下次即可使用真卡片分享。\n" +
                "点「普通分享」可用纯文本分享（无封面）。"
            )
            .setPositiveButton("去 QQ 授权") { d, _ ->
                d.dismiss()
                qqSdkSharer.login(
                    activity = this,
                    onAuthorized = {
                        AppLogger.d("MainActivity", "qq auth completed, proceed to share")
                        Toast.makeText(this, "QQ 授权成功", Toast.LENGTH_SHORT).show()
                        doQqShareAfterAuthorized(params)
                    },
                    onCancelled = {
                        Toast.makeText(this, "已取消授权", Toast.LENGTH_SHORT).show()
                    },
                    onError = { code, msg ->
                        AppLogger.e("MainActivity", "qq auth failed: code=$code msg=$msg")
                        Toast.makeText(this, "QQ 授权失败：$msg", Toast.LENGTH_LONG).show()
                        // 授权失败也兜底走系统分享，用户至少能分享出去
                        fallbackToSystemShare()
                    }
                )
            }
            .setNegativeButton("普通分享") { dialog, _ ->
                dialog.dismiss()
                fallbackToSystemShare()
            }
            .setCancelable(true)
            .show()
    }

    private fun doQqShareAfterAuthorized(params: android.os.Bundle) {
        qqSdkSharer.shareToQQ(
            activity = this,
            params = params,
            onComplete = {
                Toast.makeText(this, "已分享到 QQ", Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                AppLogger.d("MainActivity", "qq share cancelled by user")
            },
            onError = { code, msg ->
                AppLogger.e("MainActivity", "qq share onError: code=$code msg=$msg")
                when (code) {
                    com.bilibili.livemonitor.util.QQ_ERR_USER_NOT_AUTHORIZED -> {
                        // session 过期/失效（罕见，但可能发生）：重新弹引导
                        AppLogger.w("MainActivity", "qq session expired unexpectedly, re-prompt")
                        showQqAuthGuideDialog(params)
                    }
                    else -> {
                        Toast.makeText(this, "分享失败：$msg", Toast.LENGTH_LONG).show()
                        fallbackToSystemShare()
                    }
                }
            }
        )
    }

    private fun fallbackToSystemShare() {
        AppLogger.d("MainActivity", "fallback to system share")
        startActivity(Intent.createChooser(QqShare.buildSystemShareIntent(currentShareTitle), "分享直播间"))
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        AppLogger.d("MainActivity", "onActivityResult req=$requestCode result=$resultCode data=$data")
        // 把系统回调转发给 QQ SDK（callback 到当前 login 持有的 IUiListener）
        qqSdkSharer.onActivityResult(requestCode, resultCode, data)
        super.onActivityResult(requestCode, resultCode, data)
    }

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

    internal fun spaceAppIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://space/${com.bilibili.livemonitor.api.BilibiliActivityApi.MONITOR_MID}"))

    internal fun spaceWebIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/${com.bilibili.livemonitor.api.BilibiliActivityApi.MONITOR_MID}"))

    internal fun openSpace() {
        // 复用 openLiveRoom 的选择器模式：https 主 intent（浏览器列表）+
        // EXTRA_INITIAL_INTENTS 注入 bilibili://space 排最前
        val chooser = Intent.createChooser(spaceWebIntent(), "打开空间主页").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(spaceAppIntent()))
        }
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            AppLogger.w("MainActivity", "space chooser failed, fallback to web", e)
            startActivity(spaceWebIntent())
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
        // 观播静音：监控不停，本场直播结束前不提醒（正在响的铃立即停）。
        // 仅在监控中且当前在播时才置静音；无论用户选 bilibili 还是浏览器都该静音
        if (LiveCheckService.isRunning && LiveCheckService.lastLiveStatus) {
            preferenceManager.setAlertSuppressed(true)
            val muteIntent = Intent(this, LiveCheckService::class.java).apply {
                action = LiveCheckService.ACTION_WATCH_LIVE
            }
            startService(muteIntent)
            Toast.makeText(this, "已静音观播，下播后恢复提醒", Toast.LENGTH_SHORT).show()
        }
        // 主 intent：https（浏览器列表）
        // EXTRA_INITIAL_INTENTS：bilibili:// 注入，排在系统选择器最前
        val httpsIntent = liveRoomWebIntent()
        val bilibiliIntent = liveRoomAppIntent(null)
        val chooser = Intent.createChooser(httpsIntent, "打开直播间").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(bilibiliIntent))
        }
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            // 极端情况：chooser 无法启动，兜底不带包的 https
            AppLogger.w("MainActivity", "chooser failed, fallback to plain https", e)
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
