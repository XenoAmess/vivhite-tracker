package com.bilibili.livemonitor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bilibili.livemonitor.MainActivity
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.util.BilibiliDeepLinks
import com.bilibili.livemonitor.util.BiliTargets
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面小组件：一眼看白绮开播状态 + 一键进直播间。
 * 状态渲染纯函数 [buildStatus] 可单测；LiveCheckService 在状态变化时调 [updateAll] 刷新。
 */
class LiveStatusWidgetProvider : AppWidgetProvider() {

    internal enum class WidgetState { STOPPED, LIVE, NOT_LIVE, ERROR_OR_STALE }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {

        // 附加行（今日已播/上次开播）异步 DB 查询用
        private val widgetScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** Widget 渲染内容（纯数据，可单测） */
        internal data class WidgetContent(
            val iconRes: Int,
            val statusText: String,
            val liveTitle: String?,
            val showLiveTitle: Boolean
        )

        internal fun resolveState(
            monitoring: Boolean,
            lastCheckTime: Long,
            lastCheckSuccess: Boolean,
            lastCheckLive: Boolean,
            now: Long,
            staleAfterMillis: Long = STATUS_STALE_AFTER
        ): WidgetState = when {
            !monitoring -> WidgetState.STOPPED
            !lastCheckSuccess || lastCheckTime <= 0L || now - lastCheckTime > staleAfterMillis ->
                WidgetState.ERROR_OR_STALE
            lastCheckLive -> WidgetState.LIVE
            else -> WidgetState.NOT_LIVE
        }

        internal fun buildStatus(state: WidgetState): Pair<Int, String> = when (state) {
            WidgetState.STOPPED -> R.drawable.img_off to "已停止监控"
            WidgetState.LIVE -> R.drawable.img_on to "🔴 直播中"
            WidgetState.NOT_LIVE -> R.drawable.img_off to "未开播"
            WidgetState.ERROR_OR_STALE -> R.drawable.img_off to "监控异常或状态过期"
        }

        /** prefs 派生值 → 完整渲染内容；liveTitle 只在直播中且非空时展示 */
        internal fun computeContent(
            state: WidgetState,
            lastLiveTitle: String
        ): WidgetContent {
            val (iconRes, statusText) = buildStatus(state)
            val showTitle = state == WidgetState.LIVE && lastLiveTitle.isNotBlank()
            return WidgetContent(iconRes, statusText, lastLiveTitle.takeIf { showTitle }, showTitle)
        }

        /** 状态变化（handleResult/onCreate/onDestroy）时主动刷新所有实例 */
        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, LiveStatusWidgetProvider::class.java)
                )
                for (id in ids) {
                    updateWidget(context, manager, id)
                }
            } catch (e: Exception) {
                // 组件未添加时 AppWidgetManager 可能抛异常，静默即可
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = PreferenceManager(context)
            val monitoring = prefs.isServiceRunning()
            val state = resolveState(
                monitoring = monitoring,
                lastCheckTime = prefs.getLastCheckTime(),
                lastCheckSuccess = prefs.isLastCheckSuccess(),
                lastCheckLive = prefs.isLastCheckLive(),
                now = System.currentTimeMillis()
            )
            val content = computeContent(state, prefs.getLastLiveTitle())

            val views = RemoteViews(context.packageName, R.layout.widget_live_status)
            views.setImageViewResource(R.id.ivWidgetIcon, content.iconRes)
            views.setTextViewText(R.id.tvWidgetStatus, content.statusText)

            // 直播中展示当前标题（30min 周期刷新兜底服务被杀后的陈旧状态）
            if (content.showLiveTitle) {
                views.setTextViewText(R.id.tvWidgetLiveTitle, content.liveTitle)
                views.setViewVisibility(R.id.tvWidgetLiveTitle, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tvWidgetLiveTitle, android.view.View.GONE)
            }

            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openApp)

            val openLive = PendingIntent.getActivity(
                context, 1,
                BilibiliDeepLinks.liveRoomWebIntent(BiliTargets.ROOM_ID),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetOpen, openLive)

            manager.updateAppWidget(id, views)

            // 附加行（今日已播/上次开播）依赖 DB，异步查询后二次刷新；
            // 失败只丢该行，主状态已更新
            widgetScope.launch {
                try {
                    val sessions = com.bilibili.livemonitor.db.AppDatabase.get(context)
                        .streamSessionDao().recentSessions(50)
                    val extra = com.bilibili.livemonitor.domain.WidgetExtraDecider
                        .extraLine(sessions, System.currentTimeMillis())
                    val v2 = RemoteViews(context.packageName, R.layout.widget_live_status)
                    v2.setImageViewResource(R.id.ivWidgetIcon, content.iconRes)
                    v2.setTextViewText(R.id.tvWidgetStatus, content.statusText)
                    if (content.showLiveTitle) {
                        v2.setTextViewText(R.id.tvWidgetLiveTitle, content.liveTitle)
                        v2.setViewVisibility(R.id.tvWidgetLiveTitle, android.view.View.VISIBLE)
                    } else {
                        v2.setViewVisibility(R.id.tvWidgetLiveTitle, android.view.View.GONE)
                    }
                    if (extra != null) {
                        v2.setTextViewText(R.id.tvWidgetExtra, extra)
                        v2.setViewVisibility(R.id.tvWidgetExtra, android.view.View.VISIBLE)
                    } else {
                        v2.setViewVisibility(R.id.tvWidgetExtra, android.view.View.GONE)
                    }
                    v2.setOnClickPendingIntent(R.id.widgetRoot, openApp)
                    v2.setOnClickPendingIntent(R.id.btnWidgetOpen, openLive)
                    manager.updateAppWidget(id, v2)
                } catch (e: Exception) {
                    // 静默
                }
            }
        }

        internal const val STATUS_STALE_AFTER = 10 * 60_000L
    }
}
