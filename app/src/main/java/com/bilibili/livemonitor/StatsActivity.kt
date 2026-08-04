package com.bilibili.livemonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bilibili.livemonitor.databinding.ActivityStatsBinding
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.StreamStats
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直播场次统计页：最近场次列表 + 本周/本月/平均/最长汇总。
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = SessionAdapter(emptyList(), dateFormat)

        lifecycleScope.launch {
            val dao = AppDatabase.get(this@StatsActivity).streamSessionDao()
            val now = System.currentTimeMillis()
            val recent = dao.recentSessions(50)
            val summary = StreamStats.summarize(dao.closedSessionsSince(now - 30L * 86_400_000L), now)
            binding.tvStatsSummary.text = buildString {
                append("本周 ${summary.weekCount} 场 · 本月 ${summary.monthCount} 场\n")
                append("平均 ${formatDuration(summary.avgDurationMs)} · 最长 ${formatDuration(summary.maxDurationMs)} · 累计 ${formatDuration(summary.totalDurationMs)}")
            }
            binding.rvSessions.adapter = SessionAdapter(recent, dateFormat)
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--"
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }

    private class SessionAdapter(
        private val sessions: List<StreamSessionEntity>,
        private val dateFormat: SimpleDateFormat
    ) : RecyclerView.Adapter<SessionAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(R.id.tvSessionDate)
            val tvDuration: TextView = view.findViewById(R.id.tvSessionDuration)
            val tvTitle: TextView = view.findViewById(R.id.tvSessionTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_stream_session, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = sessions.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = sessions[position]
            holder.tvDate.text = dateFormat.format(Date(s.startTs))
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
