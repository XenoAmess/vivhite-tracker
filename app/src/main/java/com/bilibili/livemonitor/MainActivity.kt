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
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.domain.AlertSoundDecider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    internal lateinit var preferenceManager: PreferenceManager

    // 本地状态标志，用于立即更新UI
    private var isServiceStarting = false
    private var isServiceStopping = false

    // 标记本次会话是否已弹过权限引导，避免重复打扰
    private var hasPromptedExactAlarm = false
    // 魔法期警示条的刷新 lambda（dialog 打开时赋值，dismiss 置 null；onResume 调用）
    private var magicAlarmBannerRefresh: (() -> Unit)? = null
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
        // 把系统栏高度加进上下 padding，避免底部操作落到导航手势区。
        val basePaddingLeft = binding.root.paddingLeft
        val basePaddingTop = binding.root.paddingTop
        val basePaddingRight = binding.root.paddingRight
        val basePaddingBottom = binding.root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom
            )
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
        // 魔法期警示条：从闹钟设置页返回后自动刷新可见性
        magicAlarmBannerRefresh?.invoke()
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    override fun onDestroy() {
        magicAlarmBannerRefresh = null
        updateScope.cancel()
        shareController.cancel()
        super.onDestroy()
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

            btnStats.setOnClickListener {
                startActivity(Intent(this@MainActivity, StatsActivity::class.java))
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
        // 版本号即「关于 + 更新日志」入口（替代新增按钮，versionRow 不加第 4 个元素）
        binding.tvVersion.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    // 应用内更新：检查源为 GitHub Releases（version.json 优先，APK 文件名兜底）
    internal var updateChecker: UpdateChecker = UpdateChecker()

    private val updateScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    // 整张宣传图的 Canvas 合成与 QR 生成不能占主线程；测试可注入 Unconfined 做同步断言。
    internal var promoRenderDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

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
                title = "勿扰时段",
                subtitle = computeQuietSubtitle(),
                iconRes = android.R.drawable.ic_lock_idle_alarm,
                expandLayoutRes = R.layout.expand_section_quiet,
                onExpand = { view -> bindQuietSection(view) }
            ),
            SettingsEntry(
                title = "直播提醒",
                subtitle = "下播 / 标题变化",
                iconRes = android.R.drawable.ic_lock_idle_lock,
                expandLayoutRes = R.layout.expand_section_live_alerts,
                onExpand = { view -> bindLiveAlertsSection(view) }
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

    /** 日历单元格宽度：给外边距留量，保证 7×(cell+2m) ≤ gridWidth（周六列不被切出屏幕） */
    internal fun calendarCellSizePx(gridWidthPx: Int, marginPx: Int): Int =
        gridWidthPx / 7 - 2 * marginPx

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
        shareController.scope.launch {
            val periods = com.bilibili.livemonitor.util.MagicPeriodStore.load(preferenceManager)
            val latestEnd = periods.maxOfOrNull { it.end }
            val isOngoing = latestEnd != null && latestEnd > System.currentTimeMillis()
            val rangeText = periods.maxByOrNull { it.end }?.let {
                com.bilibili.livemonitor.util.MagicImageRenderer.formatRange(it.start, it.end)
            } ?: "还没有记录魔法期"
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.bilibili.livemonitor.util.MagicImageRenderer.render(this@MainActivity, isOngoing, rangeText)
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
            val intent = com.bilibili.livemonitor.util.ShareImageFactory.buildImageShareIntent(
                uri = uri,
                contentResolver = contentResolver,
                clipLabel = "magic",
                mimeType = "image/png",
                extraText = com.bilibili.livemonitor.domain.MagicPeriodDecider.imageText(latestEnd, System.currentTimeMillis())
            )
            startActivity(Intent.createChooser(intent, "分享魔法期图片"))
        }
    }

    /** 魔法期记录对话框：月份导航 + 7 列日历 + 联动编辑 + 记录列表 */
    /** 魔法期记录对话框：纯日历驱动——点空白日建段（自动展开编辑），点已标记的条编辑/删除 */
    internal fun showMagicPeriodDialog() {
        val periods = loadMagicPeriods()
        var selectedIndex = -1 // -1 = 未在编辑任何段；>=0 = editPanel 正在编辑的段下标
        var viewYear: Int
        var viewMonth: Int // 1-12
        java.util.Calendar.getInstance().let {
            viewYear = it.get(java.util.Calendar.YEAR)
            viewMonth = it.get(java.util.Calendar.MONTH) + 1
        }

        val view = layoutInflater.inflate(R.layout.dialog_magic_period, null)
        val grid = view.findViewById<android.widget.GridLayout>(R.id.calendarGrid)
        val tvMonth = view.findViewById<android.widget.TextView>(R.id.tvMonthTitle)
        val editPanel = view.findViewById<android.widget.LinearLayout>(R.id.editPanel)
        val tvEditTitle = view.findViewById<android.widget.TextView>(R.id.tvEditTitle)
        val btnStartDate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditStartDate)
        val btnStartTime = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditStartTime)
        val btnEndDate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditEndDate)
        val btnEndTime = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditEndTime)
        val tvDuration = view.findViewById<android.widget.TextView>(R.id.tvEditDuration)
        val magicAlarmBanner = view.findViewById<android.widget.LinearLayout>(R.id.magicAlarmBanner)
        val btnMagicAlarmPerm = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMagicAlarmPerm)

        val dialog = AlertDialog.Builder(this).setView(view).create()

        // 精确闹钟警示条：未授权显示（结束提醒降级不准点），授权/设置返回后隐藏
        fun refreshMagicAlarmBanner() {
            magicAlarmBanner.visibility =
                if (exactAlarmGranted()) android.view.View.GONE else android.view.View.VISIBLE
        }
        refreshMagicAlarmBanner()
        btnMagicAlarmPerm.setOnClickListener { openExactAlarmSettings() }
        magicAlarmBannerRefresh = { refreshMagicAlarmBanner() }
        dialog.setOnDismissListener { magicAlarmBannerRefresh = null }

        fun dayStartOf(cal: java.util.Calendar): Long = java.util.Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun markedBackground(prev: Boolean, next: Boolean): android.graphics.drawable.GradientDrawable {
            val r = 16f * resources.displayMetrics.density
            val radii = when {
                !prev && !next -> floatArrayOf(r, r, r, r, r, r, r, r) // 孤日全圆
                !prev && next -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) // 段首左圆
                prev && next -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) // 段中直角
                else -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) // 段尾右圆
            }
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(0xFF6750A4.toInt())
            }
        }

        fun refreshEditors() {
            if (selectedIndex in periods.indices) {
                editPanel.visibility = android.view.View.VISIBLE
                val p = periods[selectedIndex]
                tvEditTitle.text = "编辑这一段（${magicRangeFmt.format(java.util.Date(p.start))} 起）"
                btnStartDate.text = magicDateFmt.format(java.util.Date(p.start))
                btnStartTime.text = magicTimeFmt.format(java.util.Date(p.start))
                btnEndDate.text = magicDateFmt.format(java.util.Date(p.end))
                btnEndTime.text = magicTimeFmt.format(java.util.Date(p.end))
                tvDuration.text = com.bilibili.livemonitor.domain.MagicPeriodDecider
                    .computeDurationDays(p.start, p.end).toString()
            } else {
                editPanel.visibility = android.view.View.GONE
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
            val margin = (2 * resources.displayMetrics.density).toInt()
            // 单元格宽度必须给外边距留量，否则 7×(cell+2m) 超出网格把周六列切出屏幕
            val fallbackGridWidth = (
                resources.displayMetrics.widthPixels - (56 * resources.displayMetrics.density).toInt()
            ).coerceAtLeast(7 * (2 * margin + 1))
            val cellSize = calendarCellSizePx(
                grid.width.takeIf { it > 0 } ?: fallbackGridWidth, margin
            )
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
                val pos = com.bilibili.livemonitor.domain.MagicPeriodDecider.segmentPositionOf(periods, dayStart)
                val marked = pos != com.bilibili.livemonitor.domain.MagicPeriodDecider.SegmentPosition.NONE
                // 同段相邻 → 该侧边距 0（无缝长条）；不同段相邻 → 留缝（不粘连）
                val samePrev = com.bilibili.livemonitor.domain.MagicPeriodDecider
                    .samePeriodCovers(periods, dayStart - 86_400_000L, dayStart)
                val sameNext = com.bilibili.livemonitor.domain.MagicPeriodDecider
                    .samePeriodCovers(periods, dayStart, dayStart + 86_400_000L)
                val cell = android.widget.TextView(this).apply {
                    text = day.toString()
                    gravity = android.view.Gravity.CENTER
                    textSize = 13f
                    layoutParams = android.widget.GridLayout.LayoutParams().apply {
                        width = cellSize; height = cellSize
                        setMargins(
                            if (samePrev) 0 else margin, margin,
                            if (sameNext) 0 else margin, margin
                        )
                    }
                    if (marked) {
                        val prev = pos == com.bilibili.livemonitor.domain.MagicPeriodDecider.SegmentPosition.MIDDLE ||
                            pos == com.bilibili.livemonitor.domain.MagicPeriodDecider.SegmentPosition.LAST
                        val next = pos == com.bilibili.livemonitor.domain.MagicPeriodDecider.SegmentPosition.MIDDLE ||
                            pos == com.bilibili.livemonitor.domain.MagicPeriodDecider.SegmentPosition.FIRST
                        background = markedBackground(prev, next)
                        setTextColor(0xFFFFFFFF.toInt())
                    } else {
                        setTextColor(0xFF1A1A1A.toInt())
                    }
                    setOnClickListener {
                        if (!marked) {
                            // 点空白日：建 3 天段并自动展开编辑
                            val toggled = com.bilibili.livemonitor.domain.MagicPeriodDecider.toggleDay(periods, dayStart)
                            periods.clear(); periods.addAll(toggled)
                            selectedIndex = periods.indices.lastOrNull() ?: -1
                            saveMagicPeriods(periods)
                        } else {
                            // 点已标记的条：定位覆盖段，展开编辑
                            selectedIndex = periods.indexOfFirst {
                                com.bilibili.livemonitor.domain.MagicPeriodDecider.coversDay(it, dayStart)
                            }
                        }
                        refreshCalendar(); refreshEditors()
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

        // 段编辑：开始/结束（DatePicker + TimePicker，联动照旧）
        fun pickDateTime(isStart: Boolean, isDate: Boolean) {
            if (selectedIndex !in periods.indices) return
            val p = periods[selectedIndex]
            val base = if (isStart) p.start else p.end
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = base }
            if (isDate) {
                android.app.DatePickerDialog(this, { _, y, m, d ->
                    val newCal = java.util.Calendar.getInstance().apply {
                        timeInMillis = base; set(y, m, d)
                    }
                    val updated = if (isStart) {
                        com.bilibili.livemonitor.domain.MagicPeriodDecider.updateStart(periods, selectedIndex, newCal.timeInMillis)
                    } else {
                        com.bilibili.livemonitor.domain.MagicPeriodDecider.updateEnd(periods, selectedIndex, newCal.timeInMillis)
                    }
                    periods.clear(); periods.addAll(updated)
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar()
                }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
            } else {
                android.app.TimePickerDialog(this, { _, h, min ->
                    val newCal = java.util.Calendar.getInstance().apply {
                        timeInMillis = base
                        set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val updated = if (isStart) {
                        com.bilibili.livemonitor.domain.MagicPeriodDecider.updateStart(periods, selectedIndex, newCal.timeInMillis)
                    } else {
                        com.bilibili.livemonitor.domain.MagicPeriodDecider.updateEnd(periods, selectedIndex, newCal.timeInMillis)
                    }
                    periods.clear(); periods.addAll(updated)
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar()
                }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
            }
        }
        btnStartDate.setOnClickListener { pickDateTime(isStart = true, isDate = true) }
        btnStartTime.setOnClickListener { pickDateTime(isStart = true, isDate = false) }
        btnEndDate.setOnClickListener { pickDateTime(isStart = false, isDate = true) }
        btnEndTime.setOnClickListener { pickDateTime(isStart = false, isDate = false) }

        // 段编辑：时长 ±
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditDurMinus)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    val cur = com.bilibili.livemonitor.domain.MagicPeriodDecider
                        .computeDurationDays(periods[selectedIndex].start, periods[selectedIndex].end)
                    if (cur > 1) {
                        val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                            .updateDuration(periods, selectedIndex, cur - 1)
                        periods.clear(); periods.addAll(updated)
                        saveMagicPeriods(periods)
                        refreshEditors(); refreshCalendar()
                    }
                }
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditDurPlus)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    val cur = com.bilibili.livemonitor.domain.MagicPeriodDecider
                        .computeDurationDays(periods[selectedIndex].start, periods[selectedIndex].end)
                    val updated = com.bilibili.livemonitor.domain.MagicPeriodDecider
                        .updateDuration(periods, selectedIndex, cur + 1)
                    periods.clear(); periods.addAll(updated)
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar()
                }
            }

        // 段编辑：删除这一段
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditDelete)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    periods.removeAt(selectedIndex)
                    selectedIndex = -1
                    saveMagicPeriods(periods)
                    refreshEditors(); refreshCalendar()
                }
            }

        // 段编辑：收起
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditClose)
            .setOnClickListener {
                selectedIndex = -1
                refreshEditors(); refreshCalendar()
            }

        // 完成
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMagicDone)
            .setOnClickListener { dialog.dismiss() }

        refreshCalendar(); refreshEditors()
        dialog.show()
        // AlertDialog show 后才有真实宽度；重绘一次保证分屏/横屏用实际网格尺寸。
        grid.post { refreshCalendar() }
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

    private fun computeQuietSubtitle(): String {
        if (!preferenceManager.isQuietHoursEnabled()) return "未开启"
        val start = formatMinutes(preferenceManager.getQuietStartMinutes())
        val end = formatMinutes(preferenceManager.getQuietEndMinutes())
        return "$start → $end"
    }

    private fun formatMinutes(minutes: Int): String {
        val h = (minutes / 60).toString().padStart(2, '0')
        val m = (minutes % 60).toString().padStart(2, '0')
        return "$h:$m"
    }

    private fun bindQuietSection(view: android.view.View) {
        val switch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchQuietHours)
        val btnStart = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuietStart)
        val btnEnd = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuietEnd)

        fun refreshTimes() {
            btnStart.text = formatMinutes(preferenceManager.getQuietStartMinutes())
            btnEnd.text = formatMinutes(preferenceManager.getQuietEndMinutes())
        }
        fun setEnabled(enabled: Boolean) {
            btnStart.isEnabled = enabled
            btnEnd.isEnabled = enabled
        }

        switch.isChecked = preferenceManager.isQuietHoursEnabled()
        setEnabled(switch.isChecked)
        refreshTimes()
        switch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setQuietHoursEnabled(isChecked)
            setEnabled(isChecked)
            updateUI()
        }
        btnStart.setOnClickListener {
            pickQuietTime(preferenceManager.getQuietStartMinutes()) { minutes ->
                preferenceManager.setQuietStartMinutes(minutes)
                refreshTimes()
                updateUI()
            }
        }
        btnEnd.setOnClickListener {
            pickQuietTime(preferenceManager.getQuietEndMinutes()) { minutes ->
                preferenceManager.setQuietEndMinutes(minutes)
                refreshTimes()
                updateUI()
            }
        }
    }

    private fun pickQuietTime(initialMinutes: Int, onPicked: (Int) -> Unit) {
        val hour = initialMinutes / 60
        val minute = initialMinutes % 60
        android.app.TimePickerDialog(this, { _, h, m ->
            onPicked(h * 60 + m)
        }, hour, minute, true).show()
    }

    private fun bindLiveAlertsSection(view: android.view.View) {
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchNotifyStreamEnd).apply {
            isChecked = preferenceManager.isNotifyStreamEnd()
            setOnCheckedChangeListener { _, c -> preferenceManager.setNotifyStreamEnd(c) }
        }
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchNotifyTitleChange).apply {
            isChecked = preferenceManager.isNotifyTitleChange()
            setOnCheckedChangeListener { _, c -> preferenceManager.setNotifyTitleChange(c) }
        }
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
        bindDynamicTypeCheckbox(view, R.id.cbDynDraw, "DYNAMIC_TYPE_DRAW")
        bindDynamicTypeCheckbox(view, R.id.cbDynForward, "DYNAMIC_TYPE_FORWARD")
        bindDynamicTypeCheckbox(view, R.id.cbDynArticle, "DYNAMIC_TYPE_ARTICLE")
    }

    private fun bindDynamicTypeCheckbox(view: android.view.View, id: Int, type: String) {
        view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id).apply {
            isChecked = preferenceManager.isDynamicTypeEnabled(type)
            setOnCheckedChangeListener { _, checked ->
                val types = preferenceManager.getMonitorDynamicTypes().toMutableSet()
                if (checked) types.add(type) else types.remove(type)
                preferenceManager.setMonitorDynamicTypes(types)
            }
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
            // 增量优先：有链且底包 sha256 匹配且比全量小才走补丁，任一失败回退全量
            val localApkSha = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.bilibili.livemonitor.util.ApkPatcher.installedApkFile(this@MainActivity)
                    ?.let { com.bilibili.livemonitor.util.ApkPatcher.sha256(it) }
            }
            val plan = com.bilibili.livemonitor.domain.ChainPlanner.choosePlan(
                info.chain, localApkSha, info.apkSize
            )
            var installed = false
            if (plan is com.bilibili.livemonitor.domain.ChainPlanner.UpdatePlan.Incremental) {
                AppLogger.d("MainActivity", "incremental update: ${plan.chain.hops.size} hop(s), total ${plan.chain.totalSize} bytes")
                val updater = com.bilibili.livemonitor.util.IncrementalUpdater(this@MainActivity)
                updater.downloader = { url, d, cb -> updateChecker.downloadApk(url, d, cb) }
                val result = updater.executeChain(plan.chain, info.versionName) { percent ->
                    bar.post {
                        bar.progress = percent
                        label.text = "增量更新 $percent%"
                    }
                }
                if (result != null) {
                    installed = true
                    dialog.dismiss()
                    try {
                        startActivity(AppUpdater.buildInstallIntent(this@MainActivity, result))
                    } catch (e: Exception) {
                        AppLogger.e("MainActivity", "launch apk installer failed", e)
                        Toast.makeText(this@MainActivity, "无法打开安装器", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AppLogger.w("MainActivity", "incremental update failed, fallback to full apk")
                    label.text = "增量更新失败，转全量下载…"
                }
            }
            if (!installed) {
                val ok = updateChecker.downloadApk(info.apkUrl, dest) { percent ->
                    bar.post {
                        bar.progress = percent
                        label.text = "$percent%"
                    }
                }
                // 全量包完整性校验（version.json 带 apkSha256 时）
                val verified = ok && info.apkSha256?.let { expect ->
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.bilibili.livemonitor.util.ApkPatcher.sha256(dest)
                    }.equals(expect, ignoreCase = true).also { match ->
                        if (!match) {
                            AppLogger.w("MainActivity", "full apk sha256 mismatch, deleted")
                            dest.delete()
                        }
                    }
                } ?: ok
                dialog.dismiss()
                if (verified) {
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

    // ========== 提醒铃声自定义 ==========

    private val alertSoundProvider = AlertSoundProvider()
    // 当前分享用的直播标题（fetchRoomInfo 拿到，供 fallbackToSystemShare 使用）
    // 分享面板防抖：连点不弹多个 BottomSheet
    private var shareOptionsSheetShowing = false

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

    // internal：白绮头像获取 seam（未开播时卡片缩略图用方形头像，见 ShareController）
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

    /** 分享入口：三选一（QQ 卡片 / 图文 / 长宣传图），BottomSheet 与设置抽屉同风格 */
    internal fun showShareOptions() {
        if (shareOptionsSheetShowing) return
        shareOptionsSheetShowing = true
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
        sheet.setOnDismissListener { shareOptionsSheetShowing = false }
        sheet.show()
    }

    // ===== 分享（逻辑在 controller/ShareController，此处为委托入口，保持既有测试入口不变）=====
    private val shareController by lazy { com.bilibili.livemonitor.controller.ShareController(this) }

    internal fun shareLiveRoom() = shareController.shareLiveRoom()

    internal fun shareAsImageText() = shareController.shareAsImageText()

    internal fun shareAsQzone() = shareController.shareAsQzone()

    internal fun shareAsPromoImage() = shareController.shareAsPromoImage()

    internal fun showPromoPreview(
        cover: android.graphics.Bitmap?,
        headline: String,
        body: String,
        isLive: Boolean = false
    ) = shareController.showPromoPreview(cover, headline, body, isLive)

    internal fun copyShareLinkToClipboard() = shareController.copyShareLinkToClipboard()


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

    internal fun openSpace() {
        // 复用 openLiveRoom 的选择器模式：https 主 intent（浏览器列表）+
        // EXTRA_INITIAL_INTENTS 注入 bilibili://space 排最前
        val mid = com.bilibili.livemonitor.util.BiliTargets.MONITOR_MID
        val chooser = Intent.createChooser(com.bilibili.livemonitor.util.BilibiliDeepLinks.spaceWebIntent(mid), "打开空间主页").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(com.bilibili.livemonitor.util.BilibiliDeepLinks.spaceAppIntent(mid)))
        }
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            AppLogger.w("MainActivity", "space chooser failed, fallback to web", e)
            startActivity(com.bilibili.livemonitor.util.BilibiliDeepLinks.spaceWebIntent(mid))
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
            preferenceManager.setSuppressedLiveStart(preferenceManager.getLastLiveStartTime())
            val muteIntent = Intent(this, LiveCheckService::class.java).apply {
                action = LiveCheckService.ACTION_WATCH_LIVE
            }
            startService(muteIntent)
            Toast.makeText(this, "已静音观播，下播后恢复提醒", Toast.LENGTH_SHORT).show()
        }
        // 主 intent：https（浏览器列表）
        // EXTRA_INITIAL_INTENTS：bilibili:// 注入，排在系统选择器最前
        val httpsIntent = com.bilibili.livemonitor.util.BilibiliDeepLinks.liveRoomWebIntent(ROOM_ID)
        val bilibiliIntent = com.bilibili.livemonitor.util.BilibiliDeepLinks.liveRoomAppIntent(ROOM_ID, null)
        val chooser = Intent.createChooser(httpsIntent, "打开直播间").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(bilibiliIntent))
        }
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            // 极端情况：chooser 无法启动，兜底不带包的 https
            AppLogger.w("MainActivity", "chooser failed, fallback to plain https", e)
            startActivity(com.bilibili.livemonitor.util.BilibiliDeepLinks.liveRoomWebIntent(ROOM_ID))
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
        val generation = preferenceManager.beginMonitoringSession()

        val serviceIntent = Intent(this, LiveCheckService::class.java).apply {
            putExtra(LiveCheckService.EXTRA_ROOM_ID, ROOM_ID)
            putExtra(LiveCheckService.EXTRA_MONITORING_GENERATION, generation)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(this, "已开始监控直播间 $ROOM_ID", Toast.LENGTH_SHORT).show()

        // 使用延迟来确保Service有足够时间启动，然后清除过渡状态
        binding.root.postDelayed({
            if (!isDestroyed) {
                isServiceStarting = false
                updateUI()
            }
        }, 500)
    }

    private fun stopMonitoring() {
        val generation = preferenceManager.getMonitoringGeneration()
        preferenceManager.setServiceRunning(false)
        // 服务已死时不能为投递 STOP 再创建它；直接清理残留 Alarm/Worker 即可。
        com.bilibili.livemonitor.worker.LiveCheckWorker.cancelAll(this)
        LiveCheckService.cancelScheduledChecks(this)
        if (LiveCheckService.isRunning) {
            // STOP 绑定当前会话。用户快速重新开始后，旧 STOP 会被服务识别为过期命令。
            val stopIntent = Intent(this, LiveCheckService::class.java).apply {
                action = LiveCheckService.ACTION_STOP_SERVICE
                putExtra(LiveCheckService.EXTRA_MONITORING_GENERATION, generation)
            }
            startService(stopIntent)
        }

        Toast.makeText(this, "已停止监控", Toast.LENGTH_SHORT).show()

        // 使用延迟来确保Service有足够时间停止，然后清除过渡状态
        binding.root.postDelayed({
            if (!isDestroyed) {
                isServiceStopping = false
                updateUI()
            }
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

    // internal 注入位：单测控制精确闹钟授权态（生产为真实检查）
    internal var exactAlarmGranted: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else {
            true
        }
    }

    // 精确闹钟设置页跳转（主页面权限引导与魔法期警示条共用）
    internal fun openExactAlarmSettings() {
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

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!exactAlarmGranted()) {
                hasPromptedExactAlarm = true
                AppLogger.w("MainActivity", "exact alarm permission not granted")
                AlertDialog.Builder(this)
                    .setTitle("需要精确闹钟权限")
                    .setMessage("没有精确闹钟权限时，黑屏待机状态下检测会被系统延迟到 15 分钟一次，容易漏掉开播提醒。请授予该权限以保证每分钟检测。")
                    .setPositiveButton("去开启") { _, _ -> openExactAlarmSettings() }
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
        private const val ROOM_ID = com.bilibili.livemonitor.util.BiliTargets.ROOM_ID
        private const val GITHUB_URL = "https://github.com/XenoAmess/vivhite-tracker"

        // 自动检查更新的最小间隔：24 小时
        internal const val UPDATE_CHECK_INTERVAL_MS = 24 * 3600 * 1000L

        // 上次展示的常规池下标，防连续重复（静态保存，跨 Activity 实例有效）
        private var lastQuoteIndex: Int? = null
    }
}
