package com.bilibili.livemonitor

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bilibili.livemonitor.databinding.ActivityStatsBinding
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.MoodCatalog
import com.bilibili.livemonitor.domain.MoodTiming
import com.bilibili.livemonitor.domain.SessionBackup
import com.bilibili.livemonitor.domain.StreamStats
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 直播场次统计页：月份日历（有场次的日期高亮 + 圆点）→ 点选日期查看当天场次。
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val monthTitleFormat = SimpleDateFormat("yyyy年M月", Locale.getDefault())
    private val dayLabelFormat = SimpleDateFormat("M月d日", Locale.getDefault())

    private val cal: Calendar = Calendar.getInstance()
    private val sessionsByDay = mutableMapOf<Long, MutableList<StreamSessionEntity>>()
    private var selectedDayStart: Long = 0
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var moodAdapter: MoodEventAdapter
    private val loadedSessions = mutableListOf<StreamSessionEntity>()
    private var magicPeriods: List<com.bilibili.livemonitor.domain.MagicPeriod> = emptyList()

    // internal seam：单测注入 fake 头像加载（避免真实网络）
    internal var avatarLoader = com.bilibili.livemonitor.util.AnchorAvatarLoader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        sessionAdapter = SessionAdapter(emptyList(), timeFormat) { session ->
            showPopularityDialog(session)
        }
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

        binding.btnPrevMonth.setOnClickListener { cal.add(Calendar.MONTH, -1); renderCalendar() }
        binding.btnNextMonth.setOnClickListener { cal.add(Calendar.MONTH, 1); renderCalendar() }
        binding.btnExportStats.setOnClickListener { exportSessions() }
        binding.btnImportStats.setOnClickListener { importLauncher.launch("*/*") }
        binding.btnExportImage.setOnClickListener { exportStatsImage() }
        binding.btnStatsTrend.setOnClickListener { showStatsTrendDialog() }
        binding.btnSearchRecords.setOnClickListener { showSearchDialog() }

        loadAnchorAvatar()
        refreshData()
    }

    // 左上角主播头像：磁盘缓存优先（AnchorAvatarLoader），全空显示占位圆
    private fun loadAnchorAvatar() {
        lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(4000) { avatarLoader.load(this@StatsActivity) }
            }
            binding.ivAnchorAvatar.setImageBitmap(
                bmp?.let { avatarLoader.cropCircle(it) } ?: avatarLoader.placeholder(96)
            )
        }
    }

    // 首次加载与导入后共用的全量刷新：场次列表/摘要/柱状/日历/心情
    private fun refreshData() {
        lifecycleScope.launch {
            magicPeriods = com.bilibili.livemonitor.util.MagicPeriodStore.load(
                com.bilibili.livemonitor.util.PreferenceManager(this@StatsActivity)
            )
            val dao = AppDatabase.get(this@StatsActivity).streamSessionDao()
            val now = System.currentTimeMillis()
            val recent = dao.recentSessions(200)
            loadedSessions.clear()
            loadedSessions.addAll(recent)
            val summary = StreamStats.summarize(dao.closedSessionsSince(now - 30L * 86_400_000L), now)
            var summaryText =
                "本周 ${summary.weekCount} 场 · 本月 ${summary.monthCount} 场 · " +
                    "平均 ${formatDuration(summary.avgDurationMs)} · 最长 ${formatDuration(summary.maxDurationMs)}"
            // 星期偏好并入摘要：常播日（0=周日..6=周六）
            val localOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
            val fav = StreamStats.favoriteWeekday(recent, localOffset)
            if (fav != null) {
                summaryText += " · 常播：${WEEKDAY_NAMES[fav.first]}"
            }
            binding.tvStatsSummary.text = summaryText

            // 最近 7 天开播柱状（标签与 dailyCounts 桶逐日对齐，下标 0 = 最早一天）
            val daily = StreamStats.dailyCounts(recent, now, 7, localOffset)
            val labels = StreamStats.weekdayLabels(now, 7, localOffset).map { WEEKDAY_NAMES[it] }
            binding.weekBars.setData(daily, labels)

            sessionsByDay.clear()
            recent.forEach { s ->
                val day = dayStart(s.startTs)
                sessionsByDay.getOrPut(day) { mutableListOf() }.add(s)
            }
            // 完全没有场次时显示首次使用引导
            binding.tvEmptyGuide.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
            // 默认选中：今天；今天无场次则最近一场所在的日期
            val today = dayStart(now)
            selectedDayStart = if (sessionsByDay.containsKey(today)) today
                else (sessionsByDay.keys.maxOrNull() ?: today)
            cal.timeInMillis = selectedDayStart
            renderCalendar()
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
            height = if (header) dp(28) else dp(44)
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

    // 手账搜索弹窗：场次标题 + 心情内容，子串匹配（SessionSearch 纯函数）
    private fun showSearchDialog() {
        lifecycleScope.launch {
            val allMoods = AppDatabase.get(this@StatsActivity).moodEventDao().all()
            val view = LayoutInflater.from(this@StatsActivity)
                .inflate(R.layout.dialog_record_search, null)
            val etQuery = view.findViewById<EditText>(R.id.etSearchQuery)
            val rv = view.findViewById<RecyclerView>(R.id.rvSearchResults)
            val tvEmpty = view.findViewById<TextView>(R.id.tvSearchEmpty)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val adapter = SearchHitsAdapter(dateFmt)
            rv.layoutManager = LinearLayoutManager(this@StatsActivity)
            rv.adapter = adapter
            fun refresh() {
                val hits = com.bilibili.livemonitor.domain.SessionSearch.search(
                    loadedSessions, allMoods, etQuery.text.toString()
                )
                adapter.update(hits)
                tvEmpty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty.text = if (etQuery.text.isNullOrBlank()) "输入关键词开始搜索" else "没有匹配的记录"
            }
            etQuery.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refresh()
            })
            refresh()
            AlertDialog.Builder(this@StatsActivity)
                .setTitle("搜索手账")
                .setView(view)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private class SearchHitsAdapter(
        private val dateFmt: SimpleDateFormat
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
        }
    }

    // 观播统计弹窗：近 6 个月场次柱图 + 星期×时段开播热力
    private fun showStatsTrendDialog() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val sessions = AppDatabase.get(this@StatsActivity).streamSessionDao()
                .closedSessionsSince(now - 200L * DAY_MS) // 覆盖 6 个自然月的余量窗口
            val monthCounts = StreamStats.monthlyCounts(sessions, now, 6)
            val monthLabels = (5 downTo 0).map { back ->
                val c = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.MONTH, -back)
                }
                "${c.get(Calendar.MONTH) + 1}月"
            }
            val localOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
            val heat = StreamStats.weekdayHourHeatmap(sessions, localOffset)
            val view = LayoutInflater.from(this@StatsActivity)
                .inflate(R.layout.dialog_stats_trend, null)
            view.findViewById<com.bilibili.livemonitor.views.WeekStreamBarsView>(R.id.monthBars)
                .setData(monthCounts, monthLabels)
            view.findViewById<com.bilibili.livemonitor.views.WeekdayHourHeatmapView>(R.id.weekdayHourHeatmap)
                .setData(heat)
            AlertDialog.Builder(this@StatsActivity)
                .setTitle("观播统计")
                .setView(view)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    // 点场次行 → 人气曲线弹窗（60s 轮询采样，无数据时提示）
    private fun showPopularityDialog(session: StreamSessionEntity) {
        lifecycleScope.launch {
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

        // 事件时间：日期固定为选中日，只改时分；默认取当前时刻
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

        btnTime.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = eventTs }
            android.app.TimePickerDialog(
                this,
                { _, h, m ->
                    eventTs = selectedDayStart + h * 3_600_000L + m * 60_000L
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
                    val picked = selectedDayStart + h * 3_600_000L + m * 60_000L
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
        AlertDialog.Builder(this)
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

    // 备份导出：场次 + 心情混合 CSV（格式见 domain/SessionBackup），走 FileProvider 分享
    private fun exportSessions() {
        lifecycleScope.launch {
            val moods = AppDatabase.get(this@StatsActivity).moodEventDao().all()
            if (loadedSessions.isEmpty() && moods.isEmpty()) {
                Toast.makeText(this@StatsActivity, "暂无数据可导出", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                val file = java.io.File(dir, "vivhite_backup.csv")
                file.writeText(SessionBackup.toCsv(loadedSessions, moods), Charsets.UTF_8)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@StatsActivity, "$packageName.fileprovider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "牢白播了吗 场次+心情备份")
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
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                Toast.makeText(this@StatsActivity, "无法读取文件", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = importCsvText(text)
            AlertDialog.Builder(this@StatsActivity)
                .setTitle("导入完成")
                .setMessage(
                    "场次：新增 ${result.sessionsAdded} · 跳过重复 ${result.sessionsSkipped}\n" +
                        "心情：新增 ${result.moodsAdded} · 跳过重复 ${result.moodsSkipped}\n" +
                        "无法解析/进行中被跳过：${result.badLines} 行"
                )
                .setPositiveButton("好", null)
                .show()
            refreshData()
        }
    }

    data class ImportResult(
        val sessionsAdded: Int,
        val sessionsSkipped: Int,
        val moodsAdded: Int,
        val moodsSkipped: Int,
        val badLines: Int
    )

    /** 合并式导入：按关键字段去重（场次=起止时间，心情=时间+心情+标题），重复的跳过 */
    internal suspend fun importCsvText(text: String): ImportResult {
        val parsed = SessionBackup.parse(text)
        val db = AppDatabase.get(this)
        val sdao = db.streamSessionDao()
        val mdao = db.moodEventDao()
        var sAdded = 0
        var sSkipped = 0
        var mAdded = 0
        var mSkipped = 0
        parsed.sessions.forEach { r ->
            if (sdao.countByStartEnd(r.startTs, r.endTs) > 0) {
                sSkipped++
            } else {
                sdao.insertSession(
                    StreamSessionEntity(startTs = r.startTs, endTs = r.endTs, title = r.title)
                )
                sAdded++
            }
        }
        parsed.moods.forEach { r ->
            if (mdao.countByKey(r.eventTs, r.mood, r.title) > 0) {
                mSkipped++
            } else {
                mdao.insert(
                    MoodEventEntity(
                        eventTs = r.eventTs, durationMin = r.durationMin, mood = r.mood,
                        title = r.title, reason = r.reason, note = r.note,
                        createdAt = System.currentTimeMillis()
                    )
                )
                mAdded++
            }
        }
        return ImportResult(sAdded, sSkipped, mAdded, mSkipped, parsed.skippedLines)
    }

    // 导出图片：当月完整数据海报（摘要+柱图+日历热力+心情/魔法期+全记录），渲染后走分享面板
    private fun exportStatsImage() {
        Toast.makeText(this, "正在生成图片…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val data = buildStatsImageData()
            // 主播头像（海报左上角）：走磁盘缓存 loader，失败 null → 渲染器画占位圆，不阻断出图
            val avatar = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(4000) { avatarLoader.load(this@StatsActivity) }
            }
            val bmp = com.bilibili.livemonitor.util.StatsImageRenderer.render(
                this@StatsActivity, data, avatar
            )
            val loader = com.bilibili.livemonitor.util.ShareImageLoader()
            val file = loader.save(this@StatsActivity, bmp, "stats_share.png")
            if (file == null) {
                Toast.makeText(this@StatsActivity, "图片生成失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = loader.shareableUri(this@StatsActivity, file)
            val intent = com.bilibili.livemonitor.util.ShareImageFactory.buildImageShareIntent(
                uri, contentResolver, "stats_share", "image/png",
                extraText = "白绮 ${data.monthTitle} 绮迹手账",
                extraSubject = "牢白播了吗 绮迹手账海报"
            )
            startActivity(Intent.createChooser(intent, "分享绮迹手账"))
        }
    }

    /** 组装海报数据：纯按月维度（以当前日历所在月为准，用户可翻月） */
    /** 组装海报数据：纯按月维度（以当前日历所在月为准，用户可翻月）。
     *  组装逻辑在 util/StatsImageDataFactory（与月初自动生成共用） */
    private suspend fun buildStatsImageData(): com.bilibili.livemonitor.util.StatsImageRenderer.StatsImageData =
        com.bilibili.livemonitor.util.StatsImageDataFactory.build(this, cal)

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
            holder.itemView.setOnClickListener { onClick(s) }
        }
    }
}
