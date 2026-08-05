package com.bilibili.livemonitor.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.domain.MagicPeriod
import com.bilibili.livemonitor.domain.MagicPeriodDecider
import java.util.Calendar
import java.util.Date

/**
 * 魔法期记录对话框（从 MainActivity.showMagicPeriodDialog 拆出）。
 * 依赖通过 [show] 注入（读取/保存 prefs、精确闹钟授权态、警示条回调、日历格宽），
 * 让 MainActivity 只保留入口与注入位。
 */
class MagicPeriodDialogFragment : DialogFragment() {

    private val magicDateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    private val magicTimeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val magicRangeFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

    private lateinit var periodsLoader: () -> MutableList<MagicPeriod>
    private lateinit var periodsSaver: (List<MagicPeriod>) -> Unit
    private lateinit var exactAlarmGranted: () -> Boolean
    private lateinit var openExactAlarmSettings: () -> Unit
    private lateinit var onBannerRefresh: ((() -> Unit)?) -> Unit
    private lateinit var cellSize: (gridWidthPx: Int, marginPx: Int) -> Int

    override fun onCreateDialog(savedInstanceState: android.os.Bundle?): android.app.Dialog {
        val periods = periodsLoader()
        var selectedIndex = -1 // -1 = 未在编辑任何段；>=0 = editPanel 正在编辑的段下标
        var viewYear: Int
        var viewMonth: Int // 1-12
        Calendar.getInstance().let {
            viewYear = it.get(Calendar.YEAR)
            viewMonth = it.get(Calendar.MONTH) + 1
        }

        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_magic_period, null)
        val grid = view.findViewById<GridLayout>(R.id.calendarGrid)
        val tvMonth = view.findViewById<TextView>(R.id.tvMonthTitle)
        val editPanel = view.findViewById<LinearLayout>(R.id.editPanel)
        val tvEditTitle = view.findViewById<TextView>(R.id.tvEditTitle)
        val btnStartDate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditStartDate)
        val btnStartTime = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditStartTime)
        val btnEndDate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditEndDate)
        val btnEndTime = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditEndTime)
        val tvDuration = view.findViewById<TextView>(R.id.tvEditDuration)
        val magicAlarmBanner = view.findViewById<LinearLayout>(R.id.magicAlarmBanner)
        val btnMagicAlarmPerm = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMagicAlarmPerm)

        // 精确闹钟警示条：未授权显示（结束提醒降级不准点），授权/设置返回后隐藏
        fun refreshMagicAlarmBanner() {
            magicAlarmBanner.visibility =
                if (exactAlarmGranted()) View.GONE else View.VISIBLE
        }
        refreshMagicAlarmBanner()
        btnMagicAlarmPerm.setOnClickListener { openExactAlarmSettings() }
        onBannerRefresh { refreshMagicAlarmBanner() }

        fun dayStartOf(cal: Calendar): Long = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
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
                editPanel.visibility = View.VISIBLE
                val p = periods[selectedIndex]
                tvEditTitle.text = "编辑这一段（${magicRangeFmt.format(Date(p.start))} 起）"
                btnStartDate.text = magicDateFmt.format(Date(p.start))
                btnStartTime.text = magicTimeFmt.format(Date(p.start))
                btnEndDate.text = magicDateFmt.format(Date(p.end))
                btnEndTime.text = magicTimeFmt.format(Date(p.end))
                tvDuration.text = MagicPeriodDecider
                    .computeDurationDays(p.start, p.end).toString()
            } else {
                editPanel.visibility = View.GONE
            }
        }

        fun refreshCalendar() {
            tvMonth.text = "${viewYear}-${"%02d".format(viewMonth)}"
            grid.removeAllViews()
            val first = Calendar.getInstance().apply {
                set(viewYear, viewMonth - 1, 1, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val firstWeekday = first.get(Calendar.DAY_OF_WEEK) // 1=周日
            val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
            val margin = (2 * resources.displayMetrics.density).toInt()
            // 单元格宽度必须给外边距留量，否则 7×(cell+2m) 超出网格把周六列切出屏幕
            val fallbackGridWidth = (
                resources.displayMetrics.widthPixels - (56 * resources.displayMetrics.density).toInt()
                ).coerceAtLeast(7 * (2 * margin + 1))
            val cellW = cellSize(
                grid.width.takeIf { it > 0 } ?: fallbackGridWidth, margin
            )
            repeat(firstWeekday - 1) {
                val blank = TextView(requireActivity())
                blank.layoutParams = GridLayout.LayoutParams().apply {
                    width = cellW; height = cellW
                }
                grid.addView(blank)
            }
            for (day in 1..daysInMonth) {
                val dayCal = Calendar.getInstance().apply {
                    set(viewYear, viewMonth - 1, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayStart = dayStartOf(dayCal)
                val pos = MagicPeriodDecider.segmentPositionOf(periods, dayStart)
                val marked = pos != MagicPeriodDecider.SegmentPosition.NONE
                // 同段相邻 → 该侧边距 0（无缝长条）；不同段相邻 → 留缝（不粘连）
                val samePrev = MagicPeriodDecider
                    .samePeriodCovers(periods, dayStart - 86_400_000L, dayStart)
                val sameNext = MagicPeriodDecider
                    .samePeriodCovers(periods, dayStart, dayStart + 86_400_000L)
                val cell = TextView(requireActivity()).apply {
                    text = day.toString()
                    gravity = Gravity.CENTER
                    textSize = 13f
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = cellW; height = cellW
                        setMargins(
                            if (samePrev) 0 else margin, margin,
                            if (sameNext) 0 else margin, margin
                        )
                    }
                    if (marked) {
                        val prev = pos == MagicPeriodDecider.SegmentPosition.MIDDLE ||
                            pos == MagicPeriodDecider.SegmentPosition.LAST
                        val next = pos == MagicPeriodDecider.SegmentPosition.MIDDLE ||
                            pos == MagicPeriodDecider.SegmentPosition.FIRST
                        background = markedBackground(prev, next)
                        setTextColor(0xFFFFFFFF.toInt())
                    } else {
                        setTextColor(0xFF1A1A1A.toInt())
                    }
                    setOnClickListener {
                        if (!marked) {
                            // 点空白日：建 3 天段并自动展开编辑
                            val toggled = MagicPeriodDecider.toggleDay(periods, dayStart)
                            periods.clear(); periods.addAll(toggled)
                            selectedIndex = periods.indices.lastOrNull() ?: -1
                            periodsSaver(periods)
                        } else {
                            // 点已标记的条：定位覆盖段，展开编辑
                            selectedIndex = periods.indexOfFirst {
                                MagicPeriodDecider.coversDay(it, dayStart)
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
            val cal = Calendar.getInstance().apply { timeInMillis = base }
            if (isDate) {
                DatePickerDialog(requireActivity(), { _, y, m, d ->
                    val newCal = Calendar.getInstance().apply {
                        timeInMillis = base; set(y, m, d)
                    }
                    val updated = if (isStart) {
                        MagicPeriodDecider.updateStart(periods, selectedIndex, newCal.timeInMillis)
                    } else {
                        MagicPeriodDecider.updateEnd(periods, selectedIndex, newCal.timeInMillis)
                    }
                    periods.clear(); periods.addAll(updated)
                    periodsSaver(periods)
                    refreshEditors(); refreshCalendar()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)).show()
            } else {
                TimePickerDialog(requireActivity(), { _, h, min ->
                    val newCal = Calendar.getInstance().apply {
                        timeInMillis = base
                        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val updated = if (isStart) {
                        MagicPeriodDecider.updateStart(periods, selectedIndex, newCal.timeInMillis)
                    } else {
                        MagicPeriodDecider.updateEnd(periods, selectedIndex, newCal.timeInMillis)
                    }
                    periods.clear(); periods.addAll(updated)
                    periodsSaver(periods)
                    refreshEditors(); refreshCalendar()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
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
                    val cur = MagicPeriodDecider
                        .computeDurationDays(periods[selectedIndex].start, periods[selectedIndex].end)
                    if (cur > 1) {
                        val updated = MagicPeriodDecider
                            .updateDuration(periods, selectedIndex, cur - 1)
                        periods.clear(); periods.addAll(updated)
                        periodsSaver(periods)
                        refreshEditors(); refreshCalendar()
                    }
                }
            }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditDurPlus)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    val cur = MagicPeriodDecider
                        .computeDurationDays(periods[selectedIndex].start, periods[selectedIndex].end)
                    val updated = MagicPeriodDecider
                        .updateDuration(periods, selectedIndex, cur + 1)
                    periods.clear(); periods.addAll(updated)
                    periodsSaver(periods)
                    refreshEditors(); refreshCalendar()
                }
            }

        // 段编辑：删除这一段
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditDelete)
            .setOnClickListener {
                if (selectedIndex in periods.indices) {
                    periods.removeAt(selectedIndex)
                    selectedIndex = -1
                    periodsSaver(periods)
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
            .setOnClickListener { dismiss() }

        refreshCalendar(); refreshEditors()
        val dialog = AlertDialog.Builder(requireActivity()).setView(view).create()
        // AlertDialog show 后才有真实宽度；重绘一次保证分屏/横屏用实际网格尺寸。
        grid.post { refreshCalendar() }
        return dialog
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onBannerRefresh(null)
    }

    companion object {
        fun show(
            activity: androidx.appcompat.app.AppCompatActivity,
            periodsLoader: () -> MutableList<MagicPeriod>,
            periodsSaver: (List<MagicPeriod>) -> Unit,
            exactAlarmGranted: () -> Boolean,
            openExactAlarmSettings: () -> Unit,
            onBannerRefresh: ((() -> Unit)?) -> Unit,
            cellSize: (gridWidthPx: Int, marginPx: Int) -> Int
        ) {
            val fragment = MagicPeriodDialogFragment().apply {
                this.periodsLoader = periodsLoader
                this.periodsSaver = periodsSaver
                this.exactAlarmGranted = exactAlarmGranted
                this.openExactAlarmSettings = openExactAlarmSettings
                this.onBannerRefresh = onBannerRefresh
                this.cellSize = cellSize
            }
            // showNow：同步提交，Robolectric 下测试可立即用 ShadowDialog.getLatestDialog() 取到
            fragment.showNow(activity.supportFragmentManager, "magic_period")
        }
    }
}
