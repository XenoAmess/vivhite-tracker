package com.bilibili.livemonitor

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.bilibili.livemonitor.controller.BackupRestoreCoordinator
import com.bilibili.livemonitor.databinding.ActivityStatsBinding
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.MoodCatalog
import com.bilibili.livemonitor.domain.MoodTiming
import com.bilibili.livemonitor.domain.SessionBackup
import com.bilibili.livemonitor.domain.StreamStats
import com.bilibili.livemonitor.repository.StatsRepository
import com.bilibili.livemonitor.util.PreferenceManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 直播场次统计页：月份日历（有场次的日期高亮 + 圆点）→ 点选日期查看当天场次。
 */
class StatsActivity : AppCompatActivity() {

    private data class OverviewData(
        val allSessions: List<StreamSessionEntity>,
        val summaryText: String,
        val daily: List<Int>,
        val labels: List<String>,
        val now: Long
    )

    private lateinit var binding: ActivityStatsBinding
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val monthTitleFormat = SimpleDateFormat("yyyy年M月", Locale.getDefault())
    private val dayLabelFormat = SimpleDateFormat("M月d日", Locale.getDefault())

    private val cal: Calendar = Calendar.getInstance()
    private val sessionsByDay = mutableMapOf<Long, MutableList<StreamSessionEntity>>()
    private var selectedDayStart: Long = 0
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var moodAdapter: MoodEventAdapter
    private var magicPeriods: List<com.bilibili.livemonitor.domain.MagicPeriod> = emptyList()
    private var hasInitialSelection = false
    private var searchJob: Job? = null
    private var dataLoadJob: Job? = null
    private var monthLoadJob: Job? = null
    private var avatarLoadJob: Job? = null
    private var pendingMonth: Calendar? = null
    private var loadGeneration = 0L
    private var displayedPosterFile: java.io.File? = null
    private val pendingImportDirs = mutableSetOf<java.io.File>()
    private val database by lazy { AppDatabase.get(this) }
    private val statsRepository by lazy { StatsRepository(database) }
    private val backupRestoreCoordinator by lazy { BackupRestoreCoordinator(this, database) }

    // internal seam：单测注入 fake 头像加载（避免真实网络）
    internal var avatarLoader = com.bilibili.livemonitor.util.AnchorAvatarLoader()
    internal var moodEditDialog: AlertDialog? = null
        private set
    internal var moodDatePickerDialog: android.app.DatePickerDialog? = null
        private set
    internal var moodDeleteDialog: AlertDialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val requestedMonth = intent.getStringExtra(EXTRA_MONTH_KEY)
        when {
            savedInstanceState != null -> {
                cal.timeInMillis = savedInstanceState.getLong(STATE_MONTH, System.currentTimeMillis())
                selectedDayStart = savedInstanceState.getLong(STATE_DAY, 0L)
                hasInitialSelection = selectedDayStart != 0L
            }
            requestedMonth != null -> {
                parseMonthKey(requestedMonth)?.let {
                    cal.timeInMillis = it
                    selectedDayStart = it
                    hasInitialSelection = true
                }
            }
        }

        // targetSdk 35+ edge-to-edge：给系统栏高度加 padding，避免顶部被状态栏遮盖
        val baseL = binding.root.paddingLeft
        val baseT = binding.root.paddingTop
        val baseR = binding.root.paddingRight
        val baseB = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(baseL + bars.left, baseT + bars.top, baseR + bars.right, baseB + bars.bottom)
            insets
        }

        sessionAdapter = SessionAdapter(emptyList(), timeFormat) { session -> showSessionDialog(session) }
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = sessionAdapter

        moodAdapter = MoodEventAdapter(
            emptyList(), timeFormat,
            onClick = { showMoodEventDialog(it) },
            onDelete = { confirmDeleteMoodEvent(it) }
        )
        binding.rvMoodEvents.layoutManager = LinearLayoutManager(this)
        binding.rvMoodEvents.adapter = moodAdapter
        binding.btnAddMoodEvent.setOnClickListener { showMoodEventDialog(null) }
        binding.btnAddManualSession.setOnClickListener { showManualSessionDialog() }

        binding.btnPrevMonth.setOnClickListener { navigateMonth(-1) }
        binding.btnNextMonth.setOnClickListener { navigateMonth(1) }
        binding.btnExportImage.setOnClickListener { exportStatsImage() }
        binding.btnStatsTrend.setOnClickListener { showStatsTrendDialog() }
        binding.btnSearchRecords.setOnClickListener { showSearchDialog() }
        binding.btnMediaGallery.setOnClickListener {
            startActivity(Intent(this, MediaGalleryActivity::class.java))
        }
        binding.btnStatsMore.setOnClickListener { showMoreMenu(it) }
        binding.btnRecentPoster.setOnClickListener {
            val file = displayedPosterFile
            if (file?.isFile == true) {
                sharePoster(file)
            } else {
                updateRecentPosterEntry()
                Toast.makeText(this, "月报文件已变化，请按更新后的月份重试", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnStatsRetry.setOnClickListener { refreshData() }

        refreshData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_MONTH, cal.timeInMillis)
        outState.putLong(STATE_DAY, selectedDayStart)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pendingImportDirs.toList().forEach(::cleanupImportDir)
        super.onDestroy()
    }

    // 左上角主播头像：磁盘缓存优先（AnchorAvatarLoader），全空显示占位圆
    private fun loadAnchorAvatar(month: Calendar) {
        avatarLoadJob?.cancel()
        val requestedYear = month.get(Calendar.YEAR)
        val requestedMonth = month.get(Calendar.MONTH)
        val target = month.clone() as Calendar
        avatarLoadJob = lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(4000) {
                    avatarLoader.loadForMonth(this@StatsActivity, target)
                }
            }
            if (cal.get(Calendar.YEAR) != requestedYear || cal.get(Calendar.MONTH) != requestedMonth) return@launch
            binding.ivAnchorAvatar.setImageBitmap(
                bmp?.let { avatarLoader.cropCircle(it) } ?: avatarLoader.placeholder(96)
            )
        }
    }

    // 首次加载与导入后共用的全量刷新：场次列表/摘要/柱状/日历/心情
    private fun refreshData() {
        dataLoadJob?.cancel()
        monthLoadJob?.cancel()
        val generation = ++loadGeneration
        showLoadState("正在加载手账…", retry = false)
        dataLoadJob = lifecycleScope.launch {
            try {
                magicPeriods = com.bilibili.livemonitor.util.MagicPeriodStore.load(
                    com.bilibili.livemonitor.util.PreferenceManager(this@StatsActivity)
                )
                val overview = withContext(Dispatchers.Default) {
                    val now = System.currentTimeMillis()
                    val allSessions = statsRepository.allSessions()
                    val summary = StreamStats.summarize(
                        allSessions.filter { it.startTs >= now - 30L * DAY_MS }, now
                    )
                    var summaryText =
                        "本周 ${summary.weekCount} 场 · 本月 ${summary.monthCount} 场 · " +
                            "平均 ${formatDuration(summary.avgDurationMs)} · 最长 ${formatDuration(summary.maxDurationMs)}"
                    val localOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
                    StreamStats.favoriteWeekday(allSessions, localOffset)?.let { favorite ->
                        summaryText += " · 常播：${WEEKDAY_NAMES[favorite.first]}"
                    }
                    OverviewData(
                        allSessions = allSessions,
                        summaryText = summaryText,
                        daily = StreamStats.dailyCounts(allSessions, now, 7, localOffset),
                        labels = StreamStats.weekdayLabels(now, 7, localOffset).map { WEEKDAY_NAMES[it] },
                        now = now
                    )
                }
                if (generation != loadGeneration) return@launch
                binding.tvStatsSummary.text = overview.summaryText
                binding.weekBars.setData(overview.daily, overview.labels)

                sessionsByDay.clear()
                // 日历按月从 DB 加载（不再依赖 recent(200) 内存过滤）。
                val today = dayStart(overview.now)
                if (!hasInitialSelection) {
                    cal.timeInMillis = today
                }
                if (!loadMonthIntoMap(cal, generation)) return@launch
            // 完全没有场次时显示首次使用引导
                binding.tvEmptyGuide.visibility = if (overview.allSessions.isEmpty()) View.VISIBLE else View.GONE
            // 默认选中：今天；今天无场次则本月最近一场所在的日期
                if (!hasInitialSelection) {
                    selectedDayStart = if (sessionsByDay.containsKey(today)) today
                        else (sessionsByDay.keys.maxOrNull() ?: today)
                    hasInitialSelection = true
                }
                renderCalendar()
                loadAnchorAvatar(cal)
                binding.statsLoadState.visibility = View.GONE
                updateRecentPosterEntry()
                if (intent.getBooleanExtra(EXTRA_PREVIEW_POSTER, false)) {
                    intent.removeExtra(EXTRA_PREVIEW_POSTER)
                    val requested = intent.getStringExtra(EXTRA_POSTER_PATH)?.let { java.io.File(it) }
                    (requested?.takeIf { it.isFile } ?: latestPosterFile())?.let(::sharePoster)
                }
            } catch (e: Exception) {
                showLoadState("手账加载失败，请重试", retry = true)
            }
        }
    }

    private fun showLoadState(message: String, retry: Boolean) {
        binding.statsLoadState.visibility = View.VISIBLE
        binding.tvStatsLoadState.text = message
        binding.btnStatsRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    /** 把 target 月的场次从 DB 装进 sessionsByDay（清掉该月旧数据再装） */
    private suspend fun loadMonthIntoMap(target: Calendar, generation: Long): Boolean {
        val monthStart = (target.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val list = statsRepository.sessionsBetween(monthStart.timeInMillis, monthEnd.timeInMillis)
        if (generation != loadGeneration) return false
        sessionsByDay.keys.removeAll {
            it >= monthStart.timeInMillis && it < monthEnd.timeInMillis
        }
        // sessionsBetween 已 ASC 升序（同日场次天然时间正序）
        list.forEach { s ->
            sessionsByDay.getOrPut(dayStart(s.startTs)) { mutableListOf() }.add(s)
        }
        return true
    }

    private fun navigateMonth(delta: Int) {
        val requestedMonth = ((pendingMonth ?: cal).clone() as Calendar).apply {
            add(Calendar.MONTH, delta)
        }
        pendingMonth = requestedMonth
        loadMonthAndRender(requestedMonth)
    }

    // 翻月：数据加载完成后才提交 cal，确保标题、日历和导出月份始终一致。
    private fun loadMonthAndRender(targetMonth: Calendar) {
        dataLoadJob?.cancel()
        monthLoadJob?.cancel()
        val generation = ++loadGeneration
        val requestedMonth = targetMonth.clone() as Calendar
        monthLoadJob = lifecycleScope.launch {
            try {
                if (!loadMonthIntoMap(requestedMonth, generation)) return@launch
                if (generation != loadGeneration) return@launch
                cal.timeInMillis = requestedMonth.timeInMillis
                val monthStart = (requestedMonth.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val monthEnd = (Calendar.getInstance().apply {
                    timeInMillis = monthStart; add(Calendar.MONTH, 1)
                }).timeInMillis
                if (selectedDayStart < monthStart || selectedDayStart >= monthEnd) {
                    val today = dayStart(System.currentTimeMillis())
                    selectedDayStart = when {
                        today in monthStart until monthEnd -> today
                        // 只在本月范围内挑最近一场（全表 maxOrNull 会跳出当前月）
                        else -> sessionsByDay.keys.filter { it >= monthStart && it < monthEnd }
                            .maxOrNull() ?: monthStart
                    }
                }
                renderCalendar()
                loadAnchorAvatar(requestedMonth)
            } finally {
                if (generation == loadGeneration) pendingMonth = null
            }
        }
    }

    private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // 主题主文字色（深色模式自适应；替代硬编码 #1A1A1A）
    private fun primaryTextColor(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        return ContextCompat.getColor(this, tv.resourceId)
    }

    private fun renderCalendar() {
        binding.tvMonthTitle.text = monthTitleFormat.format(cal.time)
        binding.calendarGrid.removeAllViews()
        val activeDays = sessionsByDay.keys.count { day ->
            val c = Calendar.getInstance().apply { timeInMillis = day }
            c.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                c.get(Calendar.MONTH) == cal.get(Calendar.MONTH)
        }
        binding.calendarGrid.contentDescription =
            "${monthTitleFormat.format(cal.time)}日历，共 $activeDays 个有直播的日期"

        // 星期表头（日一...六）
        for (d in listOf("日", "一", "二", "三", "四", "五", "六")) {
            binding.calendarGrid.addView(makeCell(d, header = true))
        }

        val firstDay = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val leading = (firstDay.get(Calendar.DAY_OF_WEEK) - 1) // 周日=0
        val daysInMonth = firstDay.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = dayStart(System.currentTimeMillis())

        repeat(leading) { binding.calendarGrid.addView(makeCell("", empty = true)) }
        for (d in 1..daysInMonth) {
            val dayCal = (firstDay.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
            val day = dayStart(dayCal.timeInMillis)
            val hasSession = sessionsByDay.containsKey(day)
            val hasMagic = com.bilibili.livemonitor.domain.MagicPeriodDecider.isDayMarked(magicPeriods, day)
            binding.calendarGrid.addView(
                makeDayCell(
                    d, day, hasSession, hasMagic,
                    selected = day == selectedDayStart, isToday = day == today
                )
            )
        }
        val trailing = (7 - (leading + daysInMonth) % 7) % 7
        repeat(trailing) { binding.calendarGrid.addView(makeCell("", empty = true)) }

        showSelectedDay()
    }

    private fun makeCell(text: String, header: Boolean = false, empty: Boolean = false): TextView {
        val tv = TextView(this)
        tv.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = if (header) dp(28) else dp(48)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        }
        tv.gravity = Gravity.CENTER
        tv.text = text
        if (header) {
            tv.textSize = 12f
            tv.setTextColor(0xFF999999.toInt())
        }
        if (empty) tv.textSize = 14f
        return tv
    }

    private fun makeDayCell(
        day: Int,
        dayStart: Long,
        hasSession: Boolean,
        hasMagic: Boolean,
        selected: Boolean,
        isToday: Boolean
    ): TextView {
        val tv = makeCell(day.toString())
        val accent = ContextCompat.getColor(this, R.color.purple_500)
        val soft = 0x1F6750A4.toInt()
        val magicBg = 0xFFFCE4EC.toInt()
        val magicStroke = 0xFFF48FB1.toInt()

        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            when {
                selected -> setColor(accent)
                hasSession && hasMagic -> {
                    setColor(soft)
                    setStroke(dp(2), magicStroke)
                }
                hasSession -> setColor(soft)
                hasMagic -> setColor(magicBg)
                else -> setColor(android.graphics.Color.TRANSPARENT)
            }
        }
        tv.background = bg
        // 深色模式：默认文字跟主题主色（粉底魔法期格例外——浅底必须深字）
        tv.setTextColor(
            when {
                selected -> android.graphics.Color.WHITE
                hasMagic && !hasSession -> 0xFF1A1A1A.toInt()
                else -> primaryTextColor()
            }
        )
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val stateParts = buildList {
            if (hasSession) add("有直播")
            if (hasMagic) add("魔法期")
            if (isToday) add("今天")
            if (selected) add("已选中")
        }
        tv.contentDescription = "${dayLabelFormat.format(Date(dayStart))}" +
            if (stateParts.isEmpty()) "，无记录" else "，${stateParts.joinToString("，")}"
        if (hasSession && !selected) {
            tv.setCompoundDrawablesWithIntrinsicBounds(
                null, null, null, ContextCompat.getDrawable(this, R.drawable.dot_live)
            )
            tv.compoundDrawablePadding = 0
        } else {
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        }
        tv.setOnClickListener {
            selectedDayStart = dayStart
            renderCalendar()
        }
        return tv
    }

    private fun showSelectedDay() {
        val sessions = sessionsByDay[selectedDayStart].orEmpty()
        var label = if (sessions.isNotEmpty()) {
            "${dayLabelFormat.format(Date(selectedDayStart))} · ${sessions.size} 场直播"
        } else {
            dayLabelFormat.format(Date(selectedDayStart)) + " · 无直播"
        }
        val magicIndex = com.bilibili.livemonitor.domain.MagicPeriodDecider
            .segmentDayIndex(magicPeriods, selectedDayStart)
        if (magicIndex > 0) label += " · 魔法期第 ${magicIndex} 天"
        binding.tvSelectedDayHint.text = label
        sessionAdapter.update(sessions)
        loadTitleChanges(sessions)
        loadMoodEvents()
    }

    // 手账搜索弹窗：DAO 直接查全历史；点击结果跳到对应月/日。
    private fun showSearchDialog() {
        lifecycleScope.launch {
            try {
            val view = LayoutInflater.from(this@StatsActivity)
                .inflate(R.layout.dialog_record_search, null)
            val etQuery = view.findViewById<EditText>(R.id.etSearchQuery)
            val rv = view.findViewById<RecyclerView>(R.id.rvSearchResults)
            val tvEmpty = view.findViewById<TextView>(R.id.tvSearchEmpty)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            lateinit var dialog: AlertDialog
            val adapter = SearchHitsAdapter(dateFmt) { hit ->
                dialog.dismiss()
                navigateToTimestamp(hit.ts)
            }
            rv.layoutManager = LinearLayoutManager(this@StatsActivity)
            rv.adapter = adapter
            fun refresh() {
                val query = etQuery.text.toString().trim()
                searchJob?.cancel()
                if (query.isEmpty()) {
                    adapter.update(emptyList())
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "输入关键词开始搜索"
                    return
                }
                searchJob = lifecycleScope.launch {
                    val hits = statsRepository.search(query)
                    adapter.update(hits)
                    tvEmpty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
                    tvEmpty.text = "没有匹配的记录"
                }
            }
            etQuery.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refresh()
            })
            refresh()
            dialog = AlertDialog.Builder(this@StatsActivity)
                .setTitle("搜索手账")
                .setView(view)
                .setPositiveButton("关闭", null)
                .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@StatsActivity,
                    com.bilibili.livemonitor.util.UiMessages.DATA_LOAD_ERROR,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private class SearchHitsAdapter(
        private val dateFmt: SimpleDateFormat,
        private val onClick: (com.bilibili.livemonitor.domain.SessionSearch.Hit) -> Unit
    ) : RecyclerView.Adapter<SearchHitsAdapter.Holder>() {

        private var hits = listOf<com.bilibili.livemonitor.domain.SessionSearch.Hit>()

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        fun update(newHits: List<com.bilibili.livemonitor.domain.SessionSearch.Hit>) {
            hits = newHits
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 14, 0, 14)
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                textSize = 13f
            }
            return Holder(tv)
        }

        override fun getItemCount(): Int = hits.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val hit = hits[position]
            val kindLabel = if (hit.kind == com.bilibili.livemonitor.domain.SessionSearch.Kind.SESSION) {
                "场次"
            } else {
                "心情"
            }
            holder.text.text = "${dateFmt.format(Date(hit.ts))} · $kindLabel · ${hit.text}"
            holder.text.contentDescription = "打开${dateFmt.format(Date(hit.ts))}的$kindLabel"
            holder.text.setOnClickListener { onClick(hit) }
        }
    }

    private fun navigateToTimestamp(ts: Long) {
        selectedDayStart = dayStart(ts)
        hasInitialSelection = true
        val targetMonth = Calendar.getInstance().apply { timeInMillis = selectedDayStart }
        pendingMonth = targetMonth
        loadMonthAndRender(targetMonth)
    }

    private fun showManualSessionDialog() = showSessionDialog(null)

    // 新增/编辑场次共用；结束时间可明确落到次日，校验失败不会关闭弹窗。
    private fun showSessionDialog(existing: StreamSessionEntity?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_manual_session, null)
        val btnDate = view.findViewById<TextView>(R.id.btnManualSessionDate)
        val btnStart = view.findViewById<TextView>(R.id.btnManualSessionStart)
        val btnEnd = view.findViewById<TextView>(R.id.btnManualSessionEnd)
        val etTitle = view.findViewById<EditText>(R.id.etManualSessionTitle)
        val nextDay = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.switchManualSessionNextDay
        )
        val ongoing = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.switchManualSessionOngoing
        )
        val error = view.findViewById<TextView>(R.id.tvManualSessionError)
        val popularity = view.findViewById<View>(R.id.btnManualSessionPopularity)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        var dayStart = dayStart(existing?.startTs ?: selectedDayStart)
        var startMinutes = existing?.startTs?.let { minutesOfDay(it) } ?: 20 * 60
        var endMinutes = existing?.endTs?.let { minutesOfDay(it) } ?: 22 * 60
        var endDayOffset = existing?.endTs?.let {
            ((this@StatsActivity.dayStart(it) - dayStart) / DAY_MS).toInt().coerceAtLeast(0)
        } ?: 0
        nextDay.isChecked = endDayOffset > 0
        ongoing.isChecked = existing?.endTs == null && existing != null
        ongoing.visibility = if (existing == null) View.GONE else View.VISIBLE
        etTitle.setText(existing?.title.orEmpty())
        fun refresh() {
            btnDate.text = "日期：${dateFmt.format(Date(dayStart))}"
            btnStart.text = "开始：%02d:%02d".format(startMinutes / 60, startMinutes % 60)
            val endPrefix = when {
                endDayOffset > 1 -> "${endDayOffset}天后结束："
                nextDay.isChecked -> "次日结束："
                else -> "结束："
            }
            btnEnd.text = endPrefix +
                "%02d:%02d".format(endMinutes / 60, endMinutes % 60)
            btnEnd.isEnabled = !ongoing.isChecked
            nextDay.isEnabled = !ongoing.isChecked
            error.visibility = View.GONE
        }
        refresh()
        nextDay.setOnCheckedChangeListener { _, checked ->
            endDayOffset = if (checked) 1 else 0
            refresh()
        }
        ongoing.setOnCheckedChangeListener { _, _ -> refresh() }

        btnDate.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = dayStart }
            android.app.DatePickerDialog(
                this,
                { _, y, m, d ->
                    dayStart = Calendar.getInstance().apply {
                        set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    refresh()
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        fun pickTime(isStart: Boolean) {
            val base = if (isStart) startMinutes else endMinutes
            android.app.TimePickerDialog(
                this,
                { _, h, m ->
                    if (isStart) startMinutes = h * 60 + m else endMinutes = h * 60 + m
                    refresh()
                },
                base / 60, base % 60, true
            ).show()
        }
        btnStart.setOnClickListener { pickTime(true) }
        btnEnd.setOnClickListener { pickTime(false) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "手动补记场次" else "编辑场次")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .apply { if (existing != null) setNeutralButton("删除", null) }
            .show()

        if (existing != null) {
            popularity.visibility = View.VISIBLE
            popularity.setOnClickListener {
                dialog.dismiss()
                showPopularityDialog(existing)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                confirmDeleteSession(existing)
            }
            if (existing.endTs == null && PreferenceManager(this).isServiceRunning()) {
                error.text = "监控中的开放场次需先停止监控再修改或删除"
                error.visibility = View.VISIBLE
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val startTs = dayStart + startMinutes * 60_000L
            val endTs = if (ongoing.isChecked) null else {
                dayStart + endMinutes * 60_000L + endDayOffset * DAY_MS
            }
            if (endTs != null && endTs <= startTs) {
                error.text = "结束时间必须晚于开始时间；跨午夜请开启“结束时间在次日”"
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val title = etTitle.text.toString().trim().ifBlank { null }
            lifecycleScope.launch {
                val dao = AppDatabase.get(this@StatsActivity).streamSessionDao()
                val serviceRunning = PreferenceManager(this@StatsActivity).isServiceRunning()
                if (serviceRunning && ((existing != null && existing.endTs == null) || endTs == null)) {
                    error.text = "监控运行时不能创建、修改或删除开放场次"
                    error.visibility = View.VISIBLE
                    return@launch
                }
                val currentOpen = dao.findOpenSession()
                if (endTs == null && currentOpen != null && currentOpen.id != existing?.id) {
                    error.text = "已有开放场次，请先将其闭合"
                    error.visibility = View.VISIBLE
                    return@launch
                }
                if (existing == null) {
                    dao.insertSession(StreamSessionEntity(startTs = startTs, endTs = endTs, title = title))
                } else {
                    val updated = dao.updateDetailsIfEndUnchanged(
                        existing.id, existing.endTs, startTs, endTs, title
                    )
                    if (updated == 0) {
                        error.text = "场次已被后台更新，请关闭后重试"
                        error.visibility = View.VISIBLE
                        return@launch
                    }
                }
                selectedDayStart = this@StatsActivity.dayStart(startTs)
                cal.timeInMillis = selectedDayStart
                refreshData()
                dialog.dismiss()
            }
        }
    }

    private fun minutesOfDay(ts: Long): Int = Calendar.getInstance().apply {
        timeInMillis = ts
    }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun confirmDeleteSession(session: StreamSessionEntity) {
        AlertDialog.Builder(this)
            .setTitle("删除这场直播")
            .setMessage("将同时删除本场主题变化与人气采样，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val dao = AppDatabase.get(this@StatsActivity).streamSessionDao()
                    val current = dao.findById(session.id) ?: return@launch
                    if (current.endTs == null && PreferenceManager(this@StatsActivity).isServiceRunning()) {
                        Toast.makeText(this@StatsActivity, "请先停止监控再删除开放场次", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    dao.deleteSession(current)
                    refreshData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EXPORT_BACKUP, 0, "导出全量备份")
            menu.add(0, MENU_IMPORT_BACKUP, 1, "导入并合并备份")
            menu.add(0, MENU_MANAGE_RECORDS, 2, "批量管理记录")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EXPORT_BACKUP -> exportSessions()
                    MENU_IMPORT_BACKUP -> importLauncher.launch("*/*")
                    MENU_MANAGE_RECORDS -> showManageDialog()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun latestPosterFile(): java.io.File? =
        java.io.File(filesDir, "posters").listFiles { file ->
            file.isFile && file.name.matches(Regex("monthly_\\d{4}-\\d{2}\\.png"))
        }?.maxByOrNull { it.name }

    private fun updateRecentPosterEntry() {
        val file = latestPosterFile()
        displayedPosterFile = file
        binding.btnRecentPoster.visibility = if (file == null) View.GONE else View.VISIBLE
        val monthText = file?.name?.substringAfter("monthly_")
            ?.substringBefore(".png")?.let { key ->
                val parts = key.split('-')
                "${parts[0]}年${parts[1].toInt()}月"
            }
        binding.btnRecentPoster.text = monthText?.let { "${it}月报 · 预览/分享" }
            ?: "月报预览/分享"
        binding.btnRecentPoster.contentDescription = monthText?.let {
            "最近生成的月报：$it，预览或分享"
        } ?: "预览或分享最近生成的月报"
    }

    private fun sharePoster(file: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "牢白播了吗 绮迹手账月报")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "预览或分享月报"))
    }

    private fun parseMonthKey(key: String): Long? {
        val match = Regex("^(\\d{4})-(\\d{2})$").matchEntire(key) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        if (month !in 1..12) return null
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }.timeInMillis
    }

    // 手账批量管理：只删除已闭合场次，开放场次始终保留。
    private fun showManageDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_manage_records, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("管理记录")
            .setView(view)
            .setNegativeButton("关闭", null)
            .show()
        view.findViewById<View>(R.id.btnDeleteBeforeDate).setOnClickListener {
            dialog.dismiss()
            pickDeleteBeforeDate()
        }
        view.findViewById<View>(R.id.btnClearAllRecords).setOnClickListener {
            dialog.dismiss()
            confirmClearAll()
        }
    }

    private fun pickDeleteBeforeDate() {
        val c = Calendar.getInstance()
        android.app.DatePickerDialog(
            this,
            { _, y, m, d ->
                val before = Calendar.getInstance().apply {
                    set(y, m, d, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                confirmDeleteBefore(before)
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun confirmDeleteBefore(before: Long) {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@StatsActivity)
            val sessionCount = db.streamSessionDao().sessionsBeforeCount(before)
            val moodCount = db.moodEventDao().beforeCount(before)
            val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(before))
            AlertDialog.Builder(this@StatsActivity)
                .setTitle("删除 $dateText 之前的记录")
                .setMessage("将删除 $sessionCount 场直播场次和 $moodCount 条心情事件，此操作不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch { deleteBefore(before) }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    internal suspend fun deleteBefore(before: Long) {
        val db = AppDatabase.get(this@StatsActivity)
        db.withTransaction {
            db.streamSessionDao().deleteSessionsBefore(before)
            db.moodEventDao().deleteBefore(before)
        }
        refreshData()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("清空全部记录")
            .setMessage("将删除全部已闭合场次与心情事件；开放场次和已收藏封面会保留。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val db = AppDatabase.get(this@StatsActivity)
                    db.withTransaction {
                        db.streamSessionDao().deleteAllClosedSessions()
                        db.moodEventDao().deleteAll()
                    }
                    refreshData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 观播统计弹窗：近 6 个月场次柱图 + 星期×时段开播热力
    private fun showStatsTrendDialog() {
        lifecycleScope.launch {
            try {
            val now = System.currentTimeMillis()
            val monthStart = Calendar.getInstance().apply {
                timeInMillis = selectedDayStart
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val trend = statsRepository.trendData(
                now - 200L * DAY_MS,
                monthStart.timeInMillis,
                monthEnd.timeInMillis
            )
            val monthCounts = StreamStats.monthlyCounts(trend.sessions, now, 6)
            val monthLabels = (5 downTo 0).map { back ->
                val c = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.MONTH, -back)
                }
                "${c.get(Calendar.MONTH) + 1}月"
            }
            val localOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
            val heat = StreamStats.weekdayHourHeatmap(trend.sessions, localOffset)
            val view = LayoutInflater.from(this@StatsActivity)
                .inflate(R.layout.dialog_stats_trend, null)
            view.findViewById<com.bilibili.livemonitor.views.WeekStreamBarsView>(R.id.monthBars)
                .setData(monthCounts, monthLabels)
            view.findViewById<com.bilibili.livemonitor.views.WeekdayHourHeatmapView>(R.id.weekdayHourHeatmap)
                .setData(heat)
            // 粉丝数变化（每日快照，快照 <2 个时隐藏该区域）
            if (trend.followers.size >= 2) {
                view.findViewById<com.bilibili.livemonitor.views.PopularityChartView>(R.id.followerChart)
                    .setData(trend.followers.map { it.ts to it.followerNum.toInt() })
            } else {
                view.findViewById<View>(R.id.tvFollowerTitle).visibility = View.GONE
                view.findViewById<View>(R.id.followerChart).visibility = View.GONE
            }
            // 本月人气峰值（逐日聚合，<2 个有效日隐藏）
            val dailyPop = StreamStats.dailyPeakOnline(
                trend.popularity.map { it.ts to it.online },
                monthStart.timeInMillis, monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
            )
            if (dailyPop.size >= 2) {
                val monthLabel = "${monthStart.get(Calendar.MONTH) + 1}月"
                view.findViewById<com.bilibili.livemonitor.views.PopularityChartView>(R.id.dailyPopularityChart)
                    .setData(
                        dailyPop.map { (dom, peak) -> dom.toLong() to peak },
                        startLabel = "$monthLabel${dailyPop.first().first}日",
                        endLabel = "$monthLabel${dailyPop.last().first}日"
                    )
            } else {
                view.findViewById<View>(R.id.tvDailyPopularityTitle).visibility = View.GONE
                view.findViewById<View>(R.id.dailyPopularityChart).visibility = View.GONE
            }
            // 标题高频词云（场次标题 + 主题变化新标题；为空隐藏该区域）
            val topWords = com.bilibili.livemonitor.domain.TitleWordCloud.topWords(trend.titles)
            if (topWords.isNotEmpty()) {
                view.findViewById<com.bilibili.livemonitor.views.WordCloudView>(R.id.wordCloud)
                    .setData(topWords)
            } else {
                view.findViewById<View>(R.id.tvWordCloudTitle).visibility = View.GONE
                view.findViewById<View>(R.id.wordCloud).visibility = View.GONE
            }
            AlertDialog.Builder(this@StatsActivity)
                .setTitle("观播统计")
                .setView(view)
                .setPositiveButton("关闭", null)
                .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@StatsActivity,
                    com.bilibili.livemonitor.util.UiMessages.DATA_LOAD_ERROR,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 点场次行 → 人气曲线弹窗（60s 轮询采样，无数据时提示）
    private fun showPopularityDialog(session: StreamSessionEntity) {
        lifecycleScope.launch {
            try {
                val points = AppDatabase.get(this@StatsActivity).streamSessionDao()
                    .popularityPoints(session.id)
                if (points.size < 2) {
                    Toast.makeText(this@StatsActivity, "本场暂无人气数据", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val view = LayoutInflater.from(this@StatsActivity)
                    .inflate(R.layout.dialog_popularity_chart, null)
                view.findViewById<com.bilibili.livemonitor.views.PopularityChartView>(R.id.popularityChart)
                    .setData(points.map { it.ts to it.online })
                AlertDialog.Builder(this@StatsActivity)
                    .setTitle("本场人气曲线")
                    .setView(view)
                    .setPositiveButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@StatsActivity,
                    com.bilibili.livemonitor.util.UiMessages.DATA_LOAD_ERROR,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 当日心情事件（按日归属：[selectedDayStart, +1天)）
    private fun loadMoodEvents() {
        val day = selectedDayStart
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@StatsActivity).moodEventDao()
            val events = dao.eventsBetween(day, day + DAY_MS)
            if (day != selectedDayStart) return@launch
            moodAdapter.update(events)
            binding.tvMoodEventsEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // 添加（existing=null）/ 编辑心情事件共用的对话框
    private fun showMoodEventDialog(existing: MoodEventEntity?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_mood_event_edit, null)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupMood)
        val btnTime = view.findViewById<TextView>(R.id.btnMoodEventTime)
        val etTitle = view.findViewById<EditText>(R.id.etMoodEventTitle)
        val etReason = view.findViewById<EditText>(R.id.etMoodEventReason)
        val etNote = view.findViewById<EditText>(R.id.etMoodEventNote)

        MoodCatalog.moods.forEach { mood ->
            val chip = (LayoutInflater.from(this).inflate(R.layout.item_mood_chip, chipGroup, false) as Chip).apply {
                id = View.generateViewId()
                text = mood.emoji + mood.label
                isCheckable = true
                tag = mood.key
            }
            chipGroup.addView(chip)
            if (existing?.mood == mood.key) chipGroup.check(chip.id)
        }

        // 事件时间：日期默认选中日（可改），时分默认取当前时刻
        var eventDayStart = dayStart(existing?.eventTs ?: selectedDayStart)
        var eventTs = existing?.eventTs ?: run {
            val nowCal = Calendar.getInstance()
            selectedDayStart + nowCal.get(Calendar.HOUR_OF_DAY) * 3_600_000L +
                nowCal.get(Calendar.MINUTE) * 60_000L
        }
        var durationMin = existing?.durationMin ?: 0
        val etDuration = view.findViewById<EditText>(R.id.etMoodEventDuration)
        val btnEnd = view.findViewById<TextView>(R.id.btnMoodEventEnd)

        fun refreshTimeText() {
            btnTime.text = "开始：${timeFormat.format(Date(eventTs))}"
        }
        // 时长>0 才展示结束时间；开始/时长变 → 结束跟着变
        fun refreshEndText() {
            btnEnd.text = if (durationMin > 0) {
                "结束：${timeFormat.format(Date(MoodTiming.endTs(eventTs, durationMin)))}"
            } else {
                "结束：--"
            }
        }
        refreshTimeText()
        refreshEndText()

        val btnDate = view.findViewById<TextView>(R.id.btnMoodEventDate)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        fun refreshDateText() {
            btnDate.text = "日期：${dateFmt.format(Date(eventDayStart))}"
        }
        refreshDateText()
        btnDate.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = eventDayStart }
            android.app.DatePickerDialog(
                this,
                { _, y, m, d ->
                    val newDay = Calendar.getInstance().apply {
                        set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    // 保留时分，只换日
                    eventTs += newDay - eventDayStart
                    eventDayStart = newDay
                    refreshDateText()
                    refreshTimeText()
                    refreshEndText()
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).also { picker ->
                moodDatePickerDialog = picker
                picker.setOnDismissListener {
                    if (moodDatePickerDialog === picker) moodDatePickerDialog = null
                }
                picker.show()
            }
        }

        btnTime.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = eventTs }
            android.app.TimePickerDialog(
                this,
                { _, h, m ->
                    eventTs = eventDayStart + h * 3_600_000L + m * 60_000L
                    refreshTimeText()
                    refreshEndText() // 时长不变，结束时间跟随开始时间
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
            ).show()
        }

        // 时长 → 结束时间 联动
        etDuration.setText(if (durationMin > 0) durationMin.toString() else "")
        etDuration.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                durationMin = s?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                refreshEndText()
            }
        })

        // 结束时间 → 时长 联动（结束不晚于开始视为跨午夜）
        btnEnd.setOnClickListener {
            val base = if (durationMin > 0) MoodTiming.endTs(eventTs, durationMin) else eventTs
            val c = Calendar.getInstance().apply { timeInMillis = base }
            android.app.TimePickerDialog(
                this,
                { _, h, m ->
                    val picked = eventDayStart + h * 3_600_000L + m * 60_000L
                    durationMin = MoodTiming.durationMinFromEnd(eventTs, picked)
                    etDuration.setText(durationMin.toString()) // 触发 watcher → refreshEndText
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
            ).show()
        }

        etTitle.setText(existing?.title.orEmpty())
        etReason.setText(existing?.reason.orEmpty())
        etNote.setText(existing?.note.orEmpty())

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加心情事件" else "编辑心情事件")
            .setView(view)
            .setPositiveButton("保存", null) // 校验失败时不关闭，下面手动接管
            .setNegativeButton("取消", null)
            .show()
        moodEditDialog = dialog
        dialog.setOnDismissListener {
            if (moodEditDialog === dialog) moodEditDialog = null
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = etTitle.text.toString().trim()
            val checkedId = chipGroup.checkedChipId
            when {
                title.isEmpty() -> etTitle.error = "事件必填"
                checkedId == View.NO_ID -> Toast.makeText(this, "请选择心情", Toast.LENGTH_SHORT).show()
                else -> {
                    val moodKey = view.findViewById<Chip>(checkedId).tag as String
                    val reason = etReason.text.toString().trim().ifEmpty { null }
                    val note = etNote.text.toString().trim().ifEmpty { null }
                    lifecycleScope.launch {
                        val dao = AppDatabase.get(this@StatsActivity).moodEventDao()
                        if (existing == null) {
                            dao.insert(
                                MoodEventEntity(
                                    eventTs = eventTs, durationMin = durationMin,
                                    mood = moodKey, title = title,
                                    reason = reason, note = note,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        } else {
                            dao.update(
                                existing.copy(
                                    eventTs = eventTs, durationMin = durationMin,
                                    mood = moodKey, title = title,
                                    reason = reason, note = note
                                )
                            )
                        }
                        loadMoodEvents()
                    }
                    dialog.dismiss()
                }
            }
        }
    }

    private fun confirmDeleteMoodEvent(event: MoodEventEntity) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除心情事件")
            .setMessage("确定删除「${event.title}」吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@StatsActivity).moodEventDao().delete(event)
                    loadMoodEvents()
                }
            }
            .setNegativeButton("取消", null)
            .show()
        moodDeleteDialog = dialog
        dialog.setOnDismissListener {
            if (moodDeleteDialog === dialog) moodDeleteDialog = null
        }
    }

    // 本日主题变化时间线（stream_title_changes 已记录，这里补 UI）
    private fun loadTitleChanges(sessions: List<StreamSessionEntity>) {
        val day = selectedDayStart
        binding.tvDayTitleChanges.visibility = View.GONE
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@StatsActivity).streamSessionDao()
            val changes = sessions.flatMap { s -> dao.titleChanges(s.id) }
                .sortedBy { it.changedAt }
            if (day != selectedDayStart) return@launch
            if (changes.isEmpty()) {
                binding.tvDayTitleChanges.visibility = View.GONE
            } else {
                binding.tvDayTitleChanges.visibility = View.VISIBLE
                binding.tvDayTitleChanges.text = "本日主题变化：" + changes.joinToString("；") {
                    val time = timeFormat.format(Date(it.changedAt))
                    val from = it.oldTitle?.takeIf { t -> t.isNotBlank() }?.let { "「$it」" } ?: "开播"
                    val to = it.newTitle?.takeIf { t -> t.isNotBlank() }?.let { "「$it」" } ?: "（空）"
                    "$time $from → $to"
                }
            }
        }
    }

    // 备份导出：全量 ZIP（场次+心情+主题变化+人气点+粉丝快照+媒体历史+设置+原图），
    // 走 FileProvider 分享；internal：instrumented 测试直接调
    internal fun exportSessions() {
        lifecycleScope.launch {
            try {
                val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                val file = java.io.File(dir, "vivhite_backup.zip")
                file.outputStream().use { output ->
                    com.bilibili.livemonitor.util.FullBackupBuilder
                        .write(this@StatsActivity, output)
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@StatsActivity, "$packageName.fileprovider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "牢白播了吗 全量备份")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "导出备份"))
            } catch (e: Exception) {
                Toast.makeText(this@StatsActivity, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // SAF 选文件导入备份（mime 各家文件管理器不靠谱，用 */* 让用户都能选到）
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importFromUri(it) }
    }

    private fun importFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { raw ->
                        val input = java.io.BufferedInputStream(raw)
                        input.mark(4)
                        val first = input.read()
                        val second = input.read()
                        input.reset()
                        if (first == 'P'.code && second == 'K'.code) {
                            val tempDir = java.io.File(
                                cacheDir,
                                "import_covers_${java.util.UUID.randomUUID()}"
                            )
                            try {
                                ImportPayload.Zip(
                                    com.bilibili.livemonitor.domain.FullBackup.unpack(input, tempDir),
                                    tempDir
                                )
                            } catch (e: Exception) {
                                tempDir.deleteRecursively()
                                throw e
                            }
                        } else {
                            ImportPayload.Csv(readLegacyCsv(input))
                        }
                    } ?: throw java.io.IOException("openInputStream returned null")
                }
            }
            val payload = loaded.getOrElse { error ->
                val message = when (error) {
                    is com.bilibili.livemonitor.domain.FullBackup.IncompatibleBackupException -> error.message
                    is com.bilibili.livemonitor.domain.FullBackup.DamagedBackupException -> error.message
                    else -> "无法读取文件"
                }
                Toast.makeText(this@StatsActivity, message, Toast.LENGTH_LONG).show()
                return@launch
            }
            when (payload) {
                is ImportPayload.Zip -> {
                    pendingImportDirs += payload.tempDir
                    confirmZipImport(payload)
                }
                is ImportPayload.Csv -> {
                    val result = importCsvText(payload.text)
                    AlertDialog.Builder(this@StatsActivity)
                        .setTitle("导入完成")
                        .setMessage(
                            "场次：新增 ${result.sessionsAdded} · 补全 ${result.sessionsMerged} · " +
                                "跳过 ${result.sessionsSkipped}\n" +
                                "心情：新增 ${result.moodsAdded} · 补全 ${result.moodsMerged} · " +
                                "跳过 ${result.moodsSkipped}\n" +
                                "无法解析：${result.badLines} 行"
                        )
                        .setPositiveButton("好", null)
                        .show()
                    refreshData()
                }
            }
        }
    }

    private sealed interface ImportPayload {
        data class Zip(
            val data: com.bilibili.livemonitor.domain.FullBackup.Data,
            val tempDir: java.io.File
        ) : ImportPayload
        data class Csv(val text: String) : ImportPayload
    }

    private fun readLegacyCsv(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_LEGACY_CSV_BYTES) throw java.io.IOException("CSV 备份文件过大")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun cleanupImportDir(dir: java.io.File) {
        pendingImportDirs -= dir
        dir.deleteRecursively()
    }

    /** ZIP 全量导入：先弹确认（设置将被覆盖），用户点头才动手。 */
    private fun confirmZipImport(payload: ImportPayload.Zip) {
        val data = payload.data
        AlertDialog.Builder(this)
            .setTitle("恢复全量备份")
            .setMessage(
                "包含：场次 ${data.sessions.size} · 心情 ${data.moods.size} · " +
                    "主题变化 ${data.titleChanges.size} · 人气点 ${data.popularity.size} · " +
                    "粉丝快照 ${data.followers.size} · 媒体记录 ${data.mediaSnapshots.size}\n" +
                    "头像 ${data.avatarNames.size} 张 · 封面 ${data.coverNames.size} 张\n\n" +
                    "设置项（含魔法期、勿扰、检测频率等）将被覆盖。继续？"
            )
            .setPositiveButton("恢复") { _, _ ->
                lifecycleScope.launch {
                    try {
                        doImportZip(data)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@StatsActivity, "恢复失败，数据库未更改：${e.message}", Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        cleanupImportDir(payload.tempDir)
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> cleanupImportDir(payload.tempDir) }
            .setOnCancelListener { cleanupImportDir(payload.tempDir) }
            .show()
    }
    internal suspend fun doImportZip(
        data: com.bilibili.livemonitor.domain.FullBackup.Data
    ): com.bilibili.livemonitor.domain.FullBackup.RestoreReport {
        val report = backupRestoreCoordinator.restore(data)

        AlertDialog.Builder(this)
            .setTitle(if (data.prefsJson != null && !report.preferencesRestored) "数据已恢复，设置恢复失败" else "恢复完成")
            .setMessage(
                "场次 +${report.sessions.added} / 补全 ${report.sessions.merged} · " +
                    "心情 +${report.moods.added} / 补全 ${report.moods.merged}\n" +
                    "主题变化 +${report.titleChanges.added} / 补全 ${report.titleChanges.merged} · " +
                    "人气点 +${report.popularity.added} · 粉丝快照 +${report.followers.added} · " +
                    "媒体记录 +${report.mediaSnapshots.added} · 头像 +${report.avatars.added} · " +
                    "封面 +${report.covers.added}" +
                    if (data.prefsJson != null && !report.preferencesRestored) "\n设置快照无效，未覆盖现有设置。" else ""
            )
            .setPositiveButton("好", null)
            .show()
        refreshData()
        return report
    }

    data class ImportResult(
        val sessionsAdded: Int,
        val sessionsMerged: Int,
        val sessionsSkipped: Int,
        val moodsAdded: Int,
        val moodsMerged: Int,
        val moodsSkipped: Int,
        val badLines: Int
    )

    /** 合并式导入：按关键字段去重（场次=起止时间，心情=时间+心情+标题），重复的跳过 */
    internal suspend fun importCsvText(text: String): ImportResult = withContext(Dispatchers.IO) {
        val parsed = SessionBackup.parse(text)
        val db = AppDatabase.get(this@StatsActivity)
        val sdao = db.streamSessionDao()
        val mdao = db.moodEventDao()
        var sAdded = 0
        var sMerged = 0
        var sSkipped = 0
        var mAdded = 0
        var mMerged = 0
        var mSkipped = 0
        db.withTransaction {
            sdao.findOpenSession()?.let { newest ->
                sdao.closeOtherOpenSessions(newest.id, newest.startTs)
            }
            parsed.sessions.sortedBy { it.startTs }.forEach { r ->
                val storedEnd = if (r.endTs == null) {
                    val currentOpen = sdao.findOpenSession()
                    when {
                        currentOpen == null || currentOpen.startTs == r.startTs -> null
                        currentOpen.startTs < r.startTs -> {
                            sdao.closeOpenSessions(r.startTs)
                            null
                        }
                        else -> currentOpen.startTs
                    }
                } else {
                    r.endTs
                }
                val existing = sdao.findByStartEnd(r.startTs, storedEnd)
                if (existing == null) {
                    sdao.insertSession(
                        StreamSessionEntity(startTs = r.startTs, endTs = storedEnd, title = r.title)
                    )
                    sAdded++
                } else if (existing.title.isNullOrBlank() && !r.title.isNullOrBlank()) {
                    sdao.updateSession(existing.copy(title = r.title))
                    sMerged++
                } else sSkipped++
            }
            parsed.moods.forEach { r ->
                val existing = mdao.findByKey(r.eventTs, r.mood, r.title)
                if (existing == null) {
                    mdao.insert(
                        MoodEventEntity(
                            eventTs = r.eventTs, durationMin = r.durationMin, mood = r.mood,
                            title = r.title, reason = r.reason, note = r.note,
                            createdAt = r.createdAt
                        )
                    )
                    mAdded++
                } else {
                    val merged = existing.copy(
                        durationMin = if (existing.durationMin == 0) r.durationMin else existing.durationMin,
                        reason = existing.reason?.takeIf { it.isNotBlank() } ?: r.reason,
                        note = existing.note?.takeIf { it.isNotBlank() } ?: r.note,
                        createdAt = if (existing.createdAt <= 0) r.createdAt else existing.createdAt
                    )
                    if (merged != existing) {
                        mdao.update(merged)
                        mMerged++
                    } else mSkipped++
                }
            }
        }
        ImportResult(sAdded, sMerged, sSkipped, mAdded, mMerged, mSkipped, parsed.skippedLines)
    }

    // 导出图片：当月完整数据海报（摘要+柱图+日历热力+心情/魔法期+全记录），渲染后走分享面板
    private fun exportStatsImage() {
        val exportMonth = cal.clone() as Calendar
        Toast.makeText(this, "正在生成图片…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val data = buildStatsImageData(exportMonth)
                val avatar = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(4000) {
                        avatarLoader.loadForMonth(this@StatsActivity, exportMonth)
                    }
                }
                // Renderer measures and draws Android Views, so it must stay on the main thread.
                val bmp = com.bilibili.livemonitor.util.StatsImageRenderer.render(
                    this@StatsActivity, data, avatar
                )
                val loader = com.bilibili.livemonitor.util.ShareImageLoader()
                val file = withContext(Dispatchers.IO) {
                    loader.save(this@StatsActivity, bmp, "绮迹手账.png")
                        .also { bmp.recycle() }
                }
                if (file == null) {
                    Toast.makeText(this@StatsActivity, "图片生成失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val uri = loader.shareableUri(this@StatsActivity, file)
                val intent = com.bilibili.livemonitor.util.ShareImageFactory.buildImageShareIntent(
                    uri, contentResolver, "绮迹手账", "image/png",
                    extraText = "白绮 ${data.monthTitle} 绮迹手账",
                    extraSubject = "牢白播了吗 绮迹手账海报"
                )
                startActivity(Intent.createChooser(intent, "分享绮迹手账"))
            } catch (e: Exception) {
                Toast.makeText(this@StatsActivity, "图片生成失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 组装海报数据：纯按月维度（以当前日历所在月为准，用户可翻月）。
     *  组装逻辑在 util/StatsImageDataFactory（与月初自动生成共用） */
    private suspend fun buildStatsImageData(
        month: Calendar
    ): com.bilibili.livemonitor.util.StatsImageRenderer.StatsImageData =
        com.bilibili.livemonitor.util.StatsImageDataFactory.build(this, month)

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--"
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }

    companion object {
        // 0=周日..6=周六
        private val WEEKDAY_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        private const val DAY_MS = 86_400_000L
        private const val MAX_LEGACY_CSV_BYTES = 16L * 1024 * 1024
        private const val STATE_MONTH = "stats_month"
        private const val STATE_DAY = "stats_day"
        private const val MENU_EXPORT_BACKUP = 1
        private const val MENU_IMPORT_BACKUP = 2
        private const val MENU_MANAGE_RECORDS = 3

        const val EXTRA_MONTH_KEY = "stats_month_key"
        const val EXTRA_PREVIEW_POSTER = "stats_preview_poster"
        const val EXTRA_POSTER_PATH = "stats_poster_path"
    }

    private class MoodEventAdapter(
        private var events: List<MoodEventEntity>,
        private val timeFormat: SimpleDateFormat,
        private val onClick: (MoodEventEntity) -> Unit,
        private val onDelete: (MoodEventEntity) -> Unit
    ) : RecyclerView.Adapter<MoodEventAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvMoodEventTitle)
            val tvReason: TextView = view.findViewById(R.id.tvMoodEventReason)
            val tvNote: TextView = view.findViewById(R.id.tvMoodEventNote)
            val btnDelete: View = view.findViewById(R.id.btnMoodEventDelete)
        }

        fun update(newEvents: List<MoodEventEntity>) {
            events = newEvents
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mood_event, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = events.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = events[position]
            val start = timeFormat.format(Date(e.eventTs))
            // 时长>0 才展示结束时间
            val timePart = if (e.durationMin > 0) {
                "$start ~ ${timeFormat.format(Date(MoodTiming.endTs(e.eventTs, e.durationMin)))}"
            } else {
                start
            }
            holder.tvTitle.text = "$timePart ${MoodCatalog.display(e.mood)} · ${e.title}"
            if (e.reason.isNullOrEmpty()) {
                holder.tvReason.visibility = View.GONE
            } else {
                holder.tvReason.visibility = View.VISIBLE
                holder.tvReason.text = "原因：${e.reason}"
            }
            if (e.note.isNullOrEmpty()) {
                holder.tvNote.visibility = View.GONE
            } else {
                holder.tvNote.visibility = View.VISIBLE
                holder.tvNote.text = "备注：${e.note}"
            }
            holder.itemView.setOnClickListener { onClick(e) }
            holder.btnDelete.setOnClickListener { onDelete(e) }
        }
    }

    private class SessionAdapter(
        private var sessions: List<StreamSessionEntity>,
        private val timeFormat: SimpleDateFormat,
        private val onClick: (StreamSessionEntity) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tvSessionTime)
            val tvDuration: TextView = view.findViewById(R.id.tvSessionDuration)
            val tvTitle: TextView = view.findViewById(R.id.tvSessionTitle)
            val ivCover: android.widget.ImageView = view.findViewById(R.id.ivSessionCover)
        }

        fun update(newSessions: List<StreamSessionEntity>) {
            sessions = newSessions
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_stream_session, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = sessions.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = sessions[position]
            holder.tvTime.text = timeFormat.format(Date(s.startTs)) +
                (s.endTs?.let { " ~ ${timeFormat.format(Date(it))}" } ?: "")
            val duration = s.endTs?.let { it - s.startTs } ?: -1
            holder.tvDuration.text = if (duration >= 0) {
                val h = duration / 3_600_000
                val m = duration % 3_600_000 / 60_000
                if (h > 0) "${h}h${m}m" else "${m}min"
            } else {
                "进行中…"
            }
            holder.tvTitle.text = s.title ?: "（无标题）"
            holder.itemView.contentDescription =
                "${holder.tvTime.text}，${holder.tvDuration.text}，${holder.tvTitle.text}，点按编辑场次"
            // 当场封面缩略图（原图按控件尺寸采样解码，不改动存储）
            if (!s.coverPath.isNullOrBlank()) {
                val bmp = decodeSampled(s.coverPath, 96, 54)
                if (bmp != null) {
                    holder.ivCover.setImageBitmap(bmp)
                    holder.ivCover.visibility = View.VISIBLE
                } else {
                    holder.ivCover.visibility = View.GONE
                }
            } else {
                holder.ivCover.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onClick(s) }
        }

        // 原图按控件尺寸采样解码（不改变存储原图）
        private fun decodeSampled(path: String, reqW: Int, reqH: Int): android.graphics.Bitmap? {
            return runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
                    sample *= 2
                }
                android.graphics.BitmapFactory.decodeFile(
                    path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }
}
