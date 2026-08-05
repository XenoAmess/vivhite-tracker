package com.bilibili.livemonitor.service

import android.content.Context
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.db.StreamTitleChangeEntity
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 直播场次记录 + 主题变化追踪（从 LiveCheckService 拆出）。
 * 决策（何时记场次、何时提醒标题变化）集中在这里；DB 写发生在 [scope]
 * （服务级 IO 协程）上，通知通过回调交给 NotificationBuilder。
 */
class StreamSessionTracker(
    private val context: Context,
    private val prefs: PreferenceManager,
    private val scope: CoroutineScope,
    private val onStreamEnd: (durationMs: Long) -> Unit,
    private val onTitleChange: (newTitle: String) -> Unit
) {

    private val tag = "StreamSessionTracker"

    // 直播中主题变化提醒（默认关）：标题变化且开播超 5 分钟才提醒；记录基线到 prefs 与 DB
    fun trackTitleChange(liveTitle: String?) {
        if (liveTitle.isNullOrBlank()) return
        val lastTitle = prefs.getLastLiveTitle()
        if (liveTitle == lastTitle) return
        prefs.setLastLiveTitle(liveTitle)
        if (!prefs.isNotifyTitleChange()) return
        val startTs = parseLiveStartTime(prefs.getLastLiveStartTime()) ?: return
        if (System.currentTimeMillis() - startTs < TITLE_CHANGE_MIN_LIVE_MS) return
        onTitleChange(liveTitle)
        val dao = AppDatabase.get(context).streamSessionDao()
        scope.launch {
            try {
                dao.findOpenSession()?.let { open ->
                    dao.insertTitleChange(
                        StreamTitleChangeEntity(
                            sessionId = open.id,
                            changedAt = System.currentTimeMillis(),
                            oldTitle = lastTitle.ifBlank { null },
                            newTitle = liveTitle
                        )
                    )
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "record title change failed", e)
            }
        }
    }

    // B 站 live_start_time 可能是秒级时间戳字符串或 "yyyy-MM-dd HH:mm:ss"
    fun parseLiveStartTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val asSeconds = raw.toLongOrNull()
        if (asSeconds != null) {
            return if (asSeconds > 10_000_000_000L) asSeconds else asSeconds * 1000L
        }
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .parse(raw)?.time
        } catch (e: Exception) {
            null
        }
    }

    // 开播跳变：先闭合可能残留的未闭合场次（进程死亡），再插入新场次
    fun recordStreamStart(liveStartTime: String?, liveTitle: String?) {
        val dao = AppDatabase.get(context).streamSessionDao()
        val startTs = parseLiveStartTime(liveStartTime) ?: System.currentTimeMillis()
        scope.launch {
            try {
                dao.closeOpenSessions(startTs)
                dao.insertSession(StreamSessionEntity(startTs = startTs, title = liveTitle))
            } catch (e: Exception) {
                AppLogger.w(tag, "record stream start failed", e)
            }
        }
    }

    // 下播跳变：闭合开在场次；无开在场次（进程死亡后）用 prefs 的 live_start_time 补
    fun recordStreamEnd() {
        val dao = AppDatabase.get(context).streamSessionDao()
        val endTs = System.currentTimeMillis()
        val title = prefs.getLastLiveTitle()
        scope.launch {
            try {
                val open = dao.findOpenSession()
                val startTs = open?.startTs ?: parseLiveStartTime(prefs.getLastLiveStartTime())
                if (open != null) {
                    dao.updateSession(open.copy(endTs = endTs, title = open.title ?: title))
                } else if (startTs != null) {
                    dao.insertSession(
                        StreamSessionEntity(startTs = startTs, endTs = endTs, title = title)
                    )
                }
                if (startTs != null && prefs.isNotifyStreamEnd()) {
                    onStreamEnd(endTs - startTs)
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "record stream end failed", e)
            }
        }
    }

    private companion object {
        const val TITLE_CHANGE_MIN_LIVE_MS = 5 * 60_000L // 开播至少 5 分钟后的标题变化才提醒
    }
}
