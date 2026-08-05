package com.bilibili.livemonitor

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bilibili.livemonitor.databinding.ActivityStatsBinding
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.StreamStats
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
    private val loadedSessions = mutableListOf<StreamSessionEntity>()

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

        sessionAdapter = SessionAdapter(emptyList(), timeFormat)
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = sessionAdapter

        binding.btnPrevMonth.setOnClickListener { cal.add(Calendar.MONTH, -1); renderCalendar() }
        binding.btnNextMonth.setOnClickListener { cal.add(Calendar.MONTH, 1); renderCalendar() }
        binding.btnExportStats.setOnClickListener { exportSessions() }

        lifecycleScope.launch {
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

            // 最近 7 天开播柱状（标签取当天星期）
            val daily = StreamStats.dailyCounts(recent, now, 7, localOffset)
            val dayCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val labels = daily.indices.map { i ->
                dayCal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                dayCal.get(java.util.Calendar.DAY_OF_WEEK) - 1
            }.reversed().map { WEEKDAY_NAMES[it] }
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
            binding.calendarGrid.addView(
                makeDayCell(d, day, hasSession, selected = day == selectedDayStart, isToday = day == today)
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

    private fun makeDayCell(day: Int, dayStart: Long, hasSession: Boolean, selected: Boolean, isToday: Boolean): TextView {
        val tv = makeCell(day.toString())
        val accent = ContextCompat.getColor(this, R.color.purple_500)
        val soft = 0x1F6750A4.toInt()

        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            when {
                selected -> setColor(accent)
                hasSession -> setColor(soft)
                else -> setColor(android.graphics.Color.TRANSPARENT)
            }
        }
        tv.background = bg
        tv.setTextColor(if (selected) android.graphics.Color.WHITE else 0xFF1A1A1A.toInt())
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
        val label = if (sessions.isNotEmpty()) {
            "${dayLabelFormat.format(Date(selectedDayStart))} · ${sessions.size} 场直播"
        } else {
            dayLabelFormat.format(Date(selectedDayStart)) + " · 无直播"
        }
        binding.tvSelectedDayHint.text = label
        sessionAdapter.update(sessions)
        loadTitleChanges(sessions)
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

    // 场次导出：最近场次写 CSV 到 cacheDir/shared，走 FileProvider 分享
    private fun exportSessions() {
        if (loadedSessions.isEmpty()) {
            android.widget.Toast.makeText(this, "暂无场次可导出", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
            val file = java.io.File(dir, "sessions_export.csv")
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val header = "场次,开始,结束,时长(分钟),标题\n"
            val body = loadedSessions.sortedBy { it.startTs }.joinToString("\n") { s ->
                val start = dateFormat.format(java.util.Date(s.startTs))
                val end = s.endTs?.let { dateFormat.format(java.util.Date(it)) } ?: "进行中"
                val minutes = s.endTs?.let { (it - s.startTs) / 60_000 }?.toString() ?: ""
                val title = (s.title ?: "").replace("\"", "\"\"")
                "$start,$end,$minutes,\"$title\""
            }
            file.writeText(header + body, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "牢白播了吗 直播场次导出")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "导出直播场次"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "导出失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--"
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }

    companion object {
        // 0=周日..6=周六
        private val WEEKDAY_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    }

    private class SessionAdapter(
        private var sessions: List<StreamSessionEntity>,
        private val timeFormat: SimpleDateFormat
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
        }
    }
}
