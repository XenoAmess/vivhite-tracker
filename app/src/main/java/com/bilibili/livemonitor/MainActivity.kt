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
                ?.let { uri -> onSystemRingtonePicked(uri) }
        }
    }

    // internal：抽出来便于 Robolectric 直接测（launcher 回调无法直接触发）
    internal fun onSystemRingtonePicked(uri: Uri) {
        val title = resolveRingtoneTitle(uri)
        val encoded = AlertSoundDecider.encodeSystem(uri.toString())
        preferenceManager.setAlertSoundUri(encoded)
        preferenceManager.setAlertSoundTitle(title)
        AppLogger.d("MainActivity", "system ringtone picked: $uri ($title)")
        Toast.makeText(this, "已设置铃声：$title", Toast.LENGTH_SHORT).show()
    }

    // 音频文件 picker：SAF OPEN_DOCUMENT，返回的 uri 必须 takePersistableUriPermission
    // 否则进程被杀后读不出（这是 SAF 自定义铃声的最大坑）
    private val audioFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onAudioFilePicked(uri)
    }

    // internal：抽出来便于 Robolectric 直接测
    internal fun onAudioFilePicked(uri: Uri) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ 强制 edge-to-edge，头像会顶到状态栏下面被遮挡；
        // 把系统栏高度加进顶部 padding（保留原有 24dp 内边距，与 LogActivity 同款处理）
        val basePaddingTop = binding.root.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, basePaddingTop + bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

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

            btnMagicRecord.setOnClickListener {
                showMagicPeriodDialog()
            }

            btnMagicShare.setOnClickListener {
                shareMagicImage()
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
                showShareOptions()
            }

            btnCheckUpdate.setOnClickListener {
                checkForUpdate(manual = true)
            }

            btnBetaUpdate.setOnClickListener {
                checkBetaUpdate()
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

    // ==================== 魔法期记录 ====================

    private val magicDateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    private val magicTimeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val magicRangeFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

    private fun loadMagicPeriods(): MutableList<com.bilibili.livemonitor.domain.MagicPeriod> =
        com.bilibili.livemonitor.util.MagicPeriodStore.load(preferenceManager).toMutableList()

    private fun saveMagicPeriods(periods: List<com.bilibili.livemonitor.domain.MagicPeriod>) {
        com.bilibili.livemonitor.util.MagicPeriodStore.save(preferenceManager, periods)
        rescheduleMagicAlarm()
    }

    private fun rescheduleMagicAlarm() {
        val next = com.bilibili.livemonitor.domain.MagicPeriodDecider.nextPendingEnd(
            com.bilibili.livemonitor.util.MagicPeriodStore.load(preferenceManager),
            System.currentTimeMillis()
        )
        if (next != null) {
            com.bilibili.livemonitor.util.MagicAlarmScheduler.schedule(this, next)
        } else {
            com.bilibili.livemonitor.util.MagicAlarmScheduler.cancel(this)
        }
    }

    /** 生成并分享魔法期图片（最新未结束 → 死了啦；否则 → 复活吧） */
    internal fun shareMagicImage() {
        Toast.makeText(this, "正在生成魔法期图片…", Toast.LENGTH_SHORT).show()
        shareScope.launch {
            val periods = com.bilibili.livemonitor.util.MagicPeriodStore.load(preferenceManager)
            val latestEnd = periods.maxOfOrNull { it.end }
            val isOngoing = latestEnd != null && latestEnd > System.currentTimeMillis()
            val rangeText = periods.maxByOrNull { it.end }?.let {
                com.bilibili.livemonitor.util.MagicImageRenderer.formatRange(it.start, it.end)
            } ?: "还没有记录魔法期"
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.bilibili.livemonitor.util.MagicImageRenderer.render(isOngoing, rangeText)
            }
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                shareImageLoader.save(this@MainActivity, bmp, "magic.png")
            }
            bmp.recycle()
            if (file == null) {
                Toast.makeText(this@MainActivity, "图片生成失败", Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = shareImageLoader.shareableUri(this@MainActivity, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, com.bilibili.livemonitor.domain.MagicPeriodDecider.imageText(latestEnd, System.currentTimeMillis()))
                clipData = android.content.ClipData.newUri(contentResolver, "magic", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享魔法期图片"))
        }
    }

    /** 魔法期记录对话框：月份导航 + 7 列日历 + 联动编辑 + 记录列表 */
    internal fun showMagicPeriodDialog() {
        val periods = loadMagicPeriods()
        var selectedIndex = periods.indices.lastOrNull() ?: -1
        var viewYear: Int
        var viewMonth: Int // 1-12
        java.util.Calendar.getInstance().let {
            viewYear = it.get(java.util.Calendar.YEAR)
            viewMonth = it.get(java.util.Calendar.MONTH) + 1
        }

        val view = layoutInflater.inflate(R.layout.dialog_magic_period, null)
        val grid = view.findViewById<android.widget.GridLayout>(R.id.calendarGrid)
        val tvMonth = view.findViewById<android.widget.TextView>(R.id.tvMonthTitle)
        val btnStartDate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartDate)
        val btnStartTime = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartTime)
        val btnEndDate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEndDate)
        val btnEndTime = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEndTime)
        val tvDuration = view.findViewById<android.widget.TextView>(R.id.tvDuration)
        val listContainer = view.findViewById<android.widget.LinearLayout>(R.id.magicListContainer)

        val dialog = AlertDialog.Builder(this).setView(view).create()

        fun dayStartOf(cal: java.util.Calendar): Long = java.util.Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun refreshEditors() {
            if (selectedIndex in periods.indices) {
                val p = periods[selectedIndex]
                btnStartDate.text = magicDateFmt.format(java.util.Date(p.start))
                btnStartTime.text = magicTimeFmt.format(java.util.Date(p.start))
                btnEndDate.text = magicDateFmt.format(java.util.Date(p.end))
                btnEndTime.text = magicTimeFmt.format(java.util.Date(p.end))
                tvDuration.text = com.bilibili.livemonitor.domain.MagicPeriodDecider
                    .computeDurationDays(p.start, p.end).toString()
            } else {
                btnStartDate.text = "--"; btnStartTime.text = "--"
                btnEndDate.text = "--"; btnEndTime.text = "--"
                tvDuration.text = "3"
            }
        }

        fun refreshList() {
            listContainer.removeAllViews()
            periods.forEachIndexed { idx, p ->
                val tv = android.widget.TextView(this).apply {
                    text = "${magicRangeFmt.format(java.util.Date(p.start))}  ~  ${magicRangeFmt.format(java.util.Date(p.end))}"
                    textSize = 13f
                    setPadding(8, 8, 8, 8)
                    setTextColor(if (idx == selectedIndex) 0xFF6750A4.toInt() else 0xFF1A1A1A.toInt())
                    setOnClickListener {
                        selectedIndex = idx
                        refreshEditors(); refreshList()
                    }
                }
                listContainer.addView(tv)
            }
        }

        fun refreshCalendar() {
            tvMonth.text = "${viewYear}-${"%02d".format(viewMonth)}"
            grid.removeAllViews()
            val first = java.util.Calendar.getInstance().apply {
                set(viewYear, viewMonth - 1, 1, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val firstWeekday = first.get(java.util.Calendar.DAY_OF_WEEK) // 1=周日
            val daysInMonth = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val cellSize = grid.width.takeIf { it > 0 }?.div(7) ?: 120
            // 前置空白
            repeat(firstWeekday - 1) {
                val blank = android.widget.TextView(this)
                blank.layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cellSize; height = cellSize
                }
                grid.addView(blank)
            }
            for (day in 1..daysInMonth) {
                val dayCal = java.util.Calendar.getInstance().apply {
                    set(viewYear, viewMonth - 1, day, 0, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val dayStart = dayStartOf(dayCal)
                val marked = com.bilibili.livemonitor.domain.MagicPeriodDecider.isDayMarked(periods, dayStart)
                val cell = android.widget.TextView(this).apply {
                    text = day.toString()
                    gravity = android.view.Gravity.CENTER
                    textSize = 13f
                    layoutParams = android.widget.GridLayout.LayoutParams().apply {
                        width = cellSize; height = cellSize
                    }
                    if (marked) {
                        setBackgroundColor(0xFF6750A4.toInt())
                        setTextColor(0xFFFFFFFF.toInt())
                    } else {
                        setTextColor(0xFF1A1A1A.toInt())
                    }
                    setOnClickListener {
                        val toggled = com.bilibili.livemonitor.domain.MagicPeriodDecider.toggleDay(periods, dayStart)
                        periods.clear(); periods.addAll(toggled)
                        selectedIndex = periods.indices.lastOrNull() ?: -1
                        saveMagicPeriods(periods)
                        refreshCalendar(); refreshEditors(); refreshList()
                    }
                }
                grid.addView(cell)
            }
        }

        // 月份导航
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrevMonth)
            .setOnClickListener {
                if (viewMonth == 1) { viewYear--; viewMonth = 12 } else viewMonth--
                refreshCalendar()
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNextMonth)
            .setOnClickListener {
                if (viewMonth == 12) { viewYear++; viewMonth = 1 } else viewMonth++
                refreshCalendar()
            }

        // 开始日期/时间
        fun pickDateTime(isStart: Boolean, isDate: Boolean) {
            if (selectedIndex !in periods.indices) {
                Toast.makeText(this, "请先在日历上点选一天", Toast.LENGTH_SHORT).show()
                return
            }
            val p = periods[selectedIndex]
            val base = if (isStart) p.start else p.end
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = base }
            if (isDate) {
                android.app.DatePickerDialog(this, { _, y, m, d ->
                    val newCal = java.util.Calendar.getInstance().apply {
                        timeInMillis = base; set(y, m, d)
                    }
                    if (isStart) {
                        val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                            .updateStart(periods, selectedIndex, newCal.timeInMillis)
                        periods.clear(); periods.addAll(updated)
                    } else {
                        val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                            .updateEnd(periods, selectedIndex, newCal.timeInMillis)
                        periods.clear(); periods.addAll(updated)
                    }
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar(); refreshList()
                }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
            } else {
                android.app.TimePickerDialog(this, { _, h, min ->
                    val newCal = java.util.Calendar.getInstance().apply {
                        timeInMillis = base
                        set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    if (isStart) {
                        val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                            .updateStart(periods, selectedIndex, newCal.timeInMillis)
                        periods.clear(); periods.addAll(updated)
                    } else {
                        val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                            .updateEnd(periods, selectedIndex, newCal.timeInMillis)
                        periods.clear(); periods.addAll(updated)
                    }
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar(); refreshList()
                }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
            }
        }
        btnStartDate.setOnClickListener { pickDateTime(isStart = true, isDate = true) }
        btnStartTime.setOnClickListener { pickDateTime(isStart = true, isDate = false) }
        btnEndDate.setOnClickListener { pickDateTime(isStart = false, isDate = true) }
        btnEndTime.setOnClickListener { pickDateTime(isStart = false, isDate = false) }

        // 时长加减
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDurationMinus)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    val cur = com.bilibili.livemonitor.domain.MagicPeriodDecider
                        .computeDurationDays(periods[selectedIndex].start, periods[selectedIndex].end)
                    if (cur > 1) {
                        val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                            .updateDuration(periods, selectedIndex, cur - 1)
                        periods.clear(); periods.addAll(updated)
                        saveMagicPeriods(periods)
                        refreshEditors(); refreshCalendar(); refreshList()
                    }
                }
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDurationPlus)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    val cur = com.bilibili.livemonitor.domain.MagicPeriodDecider
                        .computeDurationDays(periods[selectedIndex].start, periods[selectedIndex].end)
                    val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                        .updateDuration(periods, selectedIndex, cur + 1)
                    periods.clear(); periods.addAll(updated)
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar(); refreshList()
                }
            }

        // 删除选中
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMagicDelete)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    periods.removeAt(selectedIndex)
                    selectedIndex = periods.indices.lastOrNull() ?: -1
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar(); refreshList()
                }
            }

        // 完成
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMagicDone)
            .setOnClickListener { dialog.dismiss() }

        refreshCalendar(); refreshEditors(); refreshList()
        dialog.show()
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
                Toast.makeText(this, "已设置铃声：${sound.title}", Toast.LENGTH_SHORT).show()
            }
            // 试听即选中：只试听不选中是真实用户踩过的坑
            //（试听听到了=以为设上了，实际没写 prefs，开播仍播默认海愿）
            btnPreview.setOnClickListener {
                item.performClick()
                previewSound(sound)
            }
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

    /**
     * 内测版尝鲜：比对 GitHub Pages 上的 master 最新构建，比本地新则下载更新。
     * 手动触发，无忽略版本/自动下载逻辑；versionCode 比较天然防降级。
     */
    internal fun checkBetaUpdate() {
        Toast.makeText(this, "正在检查内测版…", Toast.LENGTH_SHORT).show()
        updateScope.launch {
            when (val state = updateChecker.checkBetaChannel(
                BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME
            )) {
                is UpdateDecider.UpdateState.UpdateAvailable -> showUpdateDialog(state.info)
                UpdateDecider.UpdateState.UpToDate ->
                    Toast.makeText(this@MainActivity, "已是最新内测版", Toast.LENGTH_SHORT).show()
                is UpdateDecider.UpdateState.Error -> {
                    AppLogger.w("MainActivity", "beta update check failed: ${state.reason}")
                    showUpdateErrorDialog(state.reason)
                }
            }
        }
    }

    internal fun showUpdateDialog(info: UpdateDecider.ReleaseInfo) {
        val isBeta = info.tagName == com.bilibili.livemonitor.api.UpdateChecker.BETA_TAG_NAME
        val title = if (isBeta) "发现内测版 v${info.versionName}" else "发现新版本 v${info.versionName}"
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(info.changelog.trim().ifBlank { "暂无更新说明" }.take(500))
            .setPositiveButton("立即更新") { _, _ -> startUpdateDownload(info) }
            .setNegativeButton("取消", null)
        if (!isBeta) {
            // 「忽略此版本」只对正式通道有意义：beta 的 versionCode 与 stable 的
            // dismissed 逻辑互不相关，避免污染
            builder.setNeutralButton("忽略此版本") { _, _ ->
                preferenceManager.setDismissedVersionCode(info.versionCode)
            }
        }
        builder.show()
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

    private val alertSoundProvider = AlertSoundProvider()
    // 当前分享用的直播标题（fetchRoomInfo 拿到，供 fallbackToSystemShare 使用）
    private var currentShareTitle: String? = null

    // 试听播放器（internal 便于测试断言释放）。试听播放器若泄漏会一直在后台循环响
    internal var previewPlayer: ExoPlayer? = null

    // 试听播放器工厂（internal 便于测试注入 fake；Robolectric 无法构造真 ExoPlayer）
    internal var previewPlayerFactory: (android.content.Context) -> ExoPlayer = { context ->
        ExoPlayer.Builder(context).build()
    }

    // 试听内置铃声（ExoPlayer gapless 循环，停止由 stopPreview() 触发）
    private fun previewSound(sound: BuiltInSound) {
        stopPreview()
        try {
            previewPlayer = previewPlayerFactory(this).apply {
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

    // internal：分享数据获取 seam（单测注入 fake——真实 fetch 在 Robolectric 里要等满 3s 超时）
    internal var roomInfoFetcher: suspend (Long) -> com.bilibili.livemonitor.api.BilibiliApi.RoomInfo? =
        { roomId -> com.bilibili.livemonitor.api.BilibiliApi().fetchRoomInfo(roomId) }

    // internal：白绮头像获取 seam（未开播时卡片缩略图用方形头像，见 shareLiveRoom）
    internal var faceFetcher: suspend (Long) -> String? =
        { mid -> com.bilibili.livemonitor.api.BilibiliApi().fetchAnchorFace(mid) }

    // internal：分享配图加载器（单测可整体替换，避免 Bitmap.compress 等真机路径）
    internal var shareImageLoader = com.bilibili.livemonitor.util.ShareImageLoader()

    // internal：封面下载 seam（单测注入 fake 文件/bitmap，不走真网络）
    internal var coverDownloader: (String) -> java.io.File? = { url ->
        shareImageLoader.download(this, url, "cover.jpg")
    }
    internal var coverBitmapDownloader: (String) -> android.graphics.Bitmap? = { url ->
        shareImageLoader.downloadBitmap(url)
    }

    // 当前分享用的实时状态（fetch 成功用 API 的；失败回退本地缓存，供文案/兜底共用）
    private var currentShareLive: Boolean = false

    /** 分享入口：三选一（QQ 卡片 / 图文 / 长宣传图），BottomSheet 与设置抽屉同风格 */
    internal fun showShareOptions() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_share_options, null)
        view.findViewById<android.view.View>(R.id.rowShareQq).setOnClickListener {
            sheet.dismiss()
            shareLiveRoom()
        }
        view.findViewById<android.view.View>(R.id.rowShareQzone).setOnClickListener {
            sheet.dismiss()
            shareAsQzone()
        }
        view.findViewById<android.view.View>(R.id.rowShareImageText).setOnClickListener {
            sheet.dismiss()
            shareAsImageText()
        }
        view.findViewById<android.view.View>(R.id.rowSharePromo).setOnClickListener {
            sheet.dismiss()
            shareAsPromoImage()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /**
     * 分享文案的开播状态：fetch 成功用 API 实时状态（分享永远新鲜）；
     * fetch 失败回退本地缓存（监控中的服务状态，否则上次成功检测值）。
     */
    private fun resolveShareLiveState(roomInfo: com.bilibili.livemonitor.api.BilibiliApi.RoomInfo?): Boolean =
        when {
            roomInfo != null -> roomInfo.live
            LiveCheckService.isRunning -> LiveCheckService.lastLiveStatus
            else -> preferenceManager.isLastCheckSuccess() && preferenceManager.isLastCheckLive()
        }

    internal fun shareLiveRoom() {
        Toast.makeText(this, "正在生成分享卡片…", Toast.LENGTH_SHORT).show()
        // 注入 applicationContext 给 DefaultQqSdkSharer（isAuthorized/login 需要）
        (qqSdkSharer as? com.bilibili.livemonitor.util.DefaultQqSdkSharer)?.bind(applicationContext)
        shareScope.launch {
            val roomInfo = withTimeoutOrNull(3000) {
                roomInfoFetcher(QqShare.ROOM_ID)
            }
            val title = roomInfo?.title
            val isLive = resolveShareLiveState(roomInfo)
            // 缩略图策略：开播=直播封面（内容优先）；
            // 未开播/封面缺失=白绮方形头像（QQ 卡片缩略图按方形裁，16:9 封面会被切边）
            val cover = if (isLive && roomInfo?.cover != null) {
                roomInfo.cover
            } else {
                withTimeoutOrNull(3000) {
                    faceFetcher(com.bilibili.livemonitor.api.BilibiliActivityApi.MONITOR_MID)
                } ?: QqShare.FALLBACK_COVER_URL
            }
            AppLogger.d("MainActivity", "share cover=$cover title=$title live=$isLive")
            currentShareTitle = title
            currentShareLive = isLive
            val params = QqShare.buildSdkShareParams(cover, title, isLive)
            doQqShare(params)
        }
    }

    /**
     * 图文分享：状态感知文案（EXTRA_TEXT）+ 直播间封面（EXTRA_STREAM）。
     * 预研结论：QQ/微信/TIM 等聊天类应用收图片分享必丢 EXTRA_TEXT——
     * 所以文案同时烙进封面底部（renderCaptionedCover），任何目标都丢不了；
     * EXTRA_TEXT/EXTRA_SUBJECT/ClipData 保留给尊重它们的应用（微博/邮件，双保险）。
     */
    internal fun shareAsImageText() {
        Toast.makeText(this, "正在准备图文分享…", Toast.LENGTH_SHORT).show()
        shareScope.launch {
            val roomInfo = withTimeoutOrNull(3000) { roomInfoFetcher(QqShare.ROOM_ID) }
            val isLive = resolveShareLiveState(roomInfo)
            val title = roomInfo?.title
            currentShareTitle = title
            currentShareLive = isLive
            val decider = com.bilibili.livemonitor.domain.ShareTextDecider
            val caption = decider.body(isLive, QqShare.ROOM_ID, title)
            val coverBitmap = roomInfo?.cover?.let {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    coverBitmapDownloader(it)
                }
            }
            // 文案烙进封面底部半透明条带
            val captioned = coverBitmap?.let {
                com.bilibili.livemonitor.util.PromoImageRenderer.renderCaptionedCover(it, caption)
            }
            val file = captioned?.let {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    shareImageLoader.save(this@MainActivity, it, "cover_caption.png")
                }
            }
            if (file == null) {
                // 封面拿不到时降级纯文本，状态文案仍然准确
                AppLogger.w("MainActivity", "image-text share: cover unavailable, fallback to text")
                fallbackToSystemShare()
                return@launch
            }
            val uri = shareImageLoader.shareableUri(this@MainActivity, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, decider.title(isLive, title))
                putExtra(Intent.EXTRA_TEXT, "$caption ${QqShare.buildShareUrl()}")
                // ClipData 授权：部分目标只认它（不认 intent flag）才读得到图片流
                clipData = android.content.ClipData.newUri(contentResolver, "cover", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "图文分享（部分应用可能只发图片）"))
        }
    }

    /**
     * QQ空间图文说说：官方图文通道（TYPE_IMAGE_TEXT：文案+封面+链接俱全）。
     * 授权流程与 QQ 卡片共用同一 Tencent session。
     */
    internal fun shareAsQzone() {
        Toast.makeText(this, "正在准备说说…", Toast.LENGTH_SHORT).show()
        (qqSdkSharer as? com.bilibili.livemonitor.util.DefaultQqSdkSharer)?.bind(applicationContext)
        shareScope.launch {
            val roomInfo = withTimeoutOrNull(3000) { roomInfoFetcher(QqShare.ROOM_ID) }
            val isLive = resolveShareLiveState(roomInfo)
            val title = roomInfo?.title
            // QzoneShare 只收本地路径，封面先下载落盘
            val coverFile = roomInfo?.cover?.let {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    coverDownloader(it)
                }
            }
            val params = QqShare.buildQzoneShareParams(coverFile?.absolutePath, title, isLive)
            AppLogger.d("MainActivity", "shareAsQzone isAuthorized=${qqSdkSharer.isAuthorized()}")
            if (qqSdkSharer.isAuthorized()) {
                doQzoneShareAfterAuthorized(params)
            } else {
                showQqAuthGuideDialog(params) { doQzoneShareAfterAuthorized(params) }
            }
        }
    }

    private fun doQzoneShareAfterAuthorized(params: android.os.Bundle) {
        qqSdkSharer.shareToQzone(
            activity = this,
            params = params,
            onComplete = {
                Toast.makeText(this, "已分享到 QQ 空间", Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                AppLogger.d("MainActivity", "qzone share cancelled by user")
            },
            onError = { code, msg ->
                AppLogger.e("MainActivity", "qzone share onError: code=$code msg=$msg")
                if (code == com.bilibili.livemonitor.util.QQ_ERR_USER_NOT_AUTHORIZED) {
                    // session 过期：重新弹授权引导
                    showQqAuthGuideDialog(params) { doQzoneShareAfterAuthorized(params) }
                } else {
                    Toast.makeText(this, "说说分享失败：$msg", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    /**
     * 生成宣传图：封面+状态文案+直播间二维码全部烙进图里，
     * 任何分享目标都不丢文案（预研：QQ/微信会丢 EXTRA_TEXT）。
     * 先弹预览对话框，三风格即时切换（选择持久化），点分享才落盘发出。
     */
    internal fun shareAsPromoImage() {
        Toast.makeText(this, "正在生成宣传图…", Toast.LENGTH_SHORT).show()
        shareScope.launch {
            val roomInfo = withTimeoutOrNull(3000) { roomInfoFetcher(QqShare.ROOM_ID) }
            val isLive = resolveShareLiveState(roomInfo)
            val title = roomInfo?.title
            val coverBitmap = roomInfo?.cover?.let {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    coverBitmapDownloader(it)
                }
            }
            val headline = if (isLive && !title.isNullOrBlank()) "白绮开播啦！「$title」"
                           else com.bilibili.livemonitor.domain.ShareTextDecider.title(isLive, title)
            val body = com.bilibili.livemonitor.domain.ShareTextDecider.body(isLive, QqShare.ROOM_ID, title)
            showPromoPreview(coverBitmap, headline, body)
        }
    }

    /**
     * 宣传图预览对话框：50 种风格 chip 列表，点切换即时重渲染，选择持久化，点「分享」才落盘发出。
     * chip 用色点 + 名字 3 列网格（RecyclerView + GridLayoutManager）。
     */
    internal fun showPromoPreview(cover: android.graphics.Bitmap?, headline: String, body: String) {
        val view = layoutInflater.inflate(R.layout.dialog_promo_preview, null)
        val iv = view.findViewById<android.widget.ImageView>(R.id.ivPromoPreview)
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPromoStyles)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        val allStyles = com.bilibili.livemonitor.util.PromoImageRenderer.Style.values()
        val initial: com.bilibili.livemonitor.util.PromoImageRenderer.Style = runCatching {
            com.bilibili.livemonitor.util.PromoImageRenderer.Style.valueOf(preferenceManager.getPromoStyle())
        }.getOrNull() ?: com.bilibili.livemonitor.util.PromoImageRenderer.Style.LIGHT_CARD
        var current: com.bilibili.livemonitor.util.PromoImageRenderer.Style = initial
        var bitmap: android.graphics.Bitmap? = null

        fun rerender() {
            bitmap = com.bilibili.livemonitor.util.PromoImageRenderer.render(current, cover, headline, body)
            iv.setImageBitmap(bitmap)
            rv.adapter?.notifyDataSetChanged()
        }

        val names = resources.getStringArray(R.array.promo_style_names)
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_promo_style_chip, parent, false)
                ) {}

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val style = allStyles[position]
                val item = holder.itemView
                val dot = item.findViewById<android.view.View>(R.id.vChipDot)
                val name = item.findViewById<android.widget.TextView>(R.id.tvChipName)
                val check = item.findViewById<android.view.View>(R.id.vChipCheck)
                dot.setBackgroundColor(com.bilibili.livemonitor.util.PromoImageRenderer.chipColorOf(style))
                name.text = names[position]
                check.visibility = if (style == current) android.view.View.VISIBLE else android.view.View.GONE
                check.setBackgroundColor(0xFF1A1A1A.toInt())
                item.setOnClickListener {
                    if (current != style) {
                        current = style
                        preferenceManager.setPromoStyle(style.name)
                        rerender()
                    }
                }
            }

            override fun getItemCount(): Int = allStyles.size
        }
        rerender()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPromoShare)
            .setOnClickListener {
                val bmp = bitmap ?: return@setOnClickListener
                dialog.dismiss()
                sharePromoBitmap(bmp, body)
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPromoCancel)
            .setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun sharePromoBitmap(promo: android.graphics.Bitmap, body: String) {
        Toast.makeText(this, "正在准备分享…", Toast.LENGTH_SHORT).show()
        shareScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                shareImageLoader.save(this@MainActivity, promo, "promo.png")
            }
            promo.recycle()
            if (file == null) {
                Toast.makeText(this@MainActivity, "宣传图生成失败", Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = shareImageLoader.shareableUri(this@MainActivity, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "$body ${QqShare.buildShareUrl()}")
                clipData = android.content.ClipData.newUri(contentResolver, "promo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享宣传图"))
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

    private fun showQqAuthGuideDialog(
        params: android.os.Bundle,
        onAuthorizedProceed: () -> Unit = { doQqShareAfterAuthorized(params) }
    ) {
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
                        onAuthorizedProceed()
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
        startActivity(
            Intent.createChooser(
                QqShare.buildSystemShareIntent(currentShareTitle, currentShareLive),
                "分享直播间"
            )
        )
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

    // internal：Robolectric shadow 不支持 shouldShowRequestPermissionRationale，
    // 抽出便于测试 rationale 弹窗分支
    internal var notificationRationaleChecker: () -> Boolean = {
        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> true
                notificationRationaleChecker() -> {
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
