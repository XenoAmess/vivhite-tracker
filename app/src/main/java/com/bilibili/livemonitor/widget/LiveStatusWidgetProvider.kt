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

/**
 * 桌面小组件：一眼看白绮开播状态 + 一键进直播间。
 * 状态渲染纯函数 [buildStatus] 可单测；LiveCheckService 在状态变化时调 [updateAll] 刷新。
 */
class LiveStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {

        // 纯渲染决策（可单测）：monitoring + live → 图标/文案
        internal fun buildStatus(monitoring: Boolean, live: Boolean): Pair<Int, String> = when {
            !monitoring -> R.drawable.img_off to "已停止监控"
            live -> R.drawable.img_on to "🔴 直播中"
            else -> R.drawable.img_off to "未开播"
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
            // 用持久化的最近检测结果（进程重启后静态变量为 false，prefs 才可靠）
            val live = monitoring && prefs.isLastCheckSuccess() && prefs.isLastCheckLive()
            val (iconRes, statusText) = buildStatus(monitoring, live)

            val views = RemoteViews(context.packageName, R.layout.widget_live_status)
            views.setImageViewResource(R.id.ivWidgetIcon, iconRes)
            views.setTextViewText(R.id.tvWidgetStatus, statusText)

            // 直播中展示当前标题（30min 周期刷新兜底服务被杀后的陈旧状态）
            val liveTitle = prefs.getLastLiveTitle()
            if (live && liveTitle.isNotBlank()) {
                views.setTextViewText(R.id.tvWidgetLiveTitle, liveTitle)
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
        }
    }
}
