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

    // 开播跳变：先闭合可能残留的未闭合场次（进程死亡），再插入新场次。
    // 幂等：进程死亡恢复后若开放行与本场 liveStartTime 相同（同一场），
    // 直接复用不闭合不新插——否则残留行会被塌缩成 0 分钟幽灵场次（升级场景 A'）
    fun recordStreamStart(liveStartTime: String?, liveTitle: String?) {
        val dao = AppDatabase.get(context).streamSessionDao()
        val parsedStart = parseLiveStartTime(liveStartTime)
        val startTs = parsedStart ?: System.currentTimeMillis()
        scope.launch {
            try {
                val open = dao.findOpenSession()
                if (open != null && parsedStart != null && open.startTs == parsedStart) {
                    AppLogger.d(tag, "record stream start: same session ${open.id}, reuse")
                    return@launch
                }
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

    /**
     * NotLive reconcile（进程死亡跨过下播的场景）：状态恢复超龄后
     * recordStreamEnd 不会被调（无跳变），残留开放行会挂到下一场开播被
     * 错闭合成数天长的假场次。这里在每次 NotLive 检测时静默补闭合：
     * 闭合点 = 最后一次确认在播的检测时间（存活证据上限），无证据或证据
     * 早于开场则夹到开场（0 时长行，统计层 endTs>startTs 过滤，诚实"未知"）。
     * 静默：不触发下播通知。与 recordStreamEnd 由 wasLive 门控天然互斥。
     */
    fun reconcileOpenSessionIfNotLive() {
        val dao = AppDatabase.get(context).streamSessionDao()
        scope.launch {
            try {
                val open = dao.findOpenSession() ?: return@launch
                val observed = prefs.getLastLiveObservedTime()
                val endTs = reconcileEndTs(open.startTs, observed)
                dao.updateSession(open.copy(endTs = endTs, title = open.title ?: prefs.getLastLiveTitle()))
                AppLogger.d(tag, "reconciled stale open session ${open.id}, endTs=$endTs")
            } catch (e: Exception) {
                AppLogger.w(tag, "reconcile open session failed", e)
            }
        }
    }

    internal fun reconcileEndTs(openStartTs: Long, lastLiveObservedMs: Long): Long =
        if (lastLiveObservedMs >= openStartTs) lastLiveObservedMs else openStartTs

    private companion object {
        const val TITLE_CHANGE_MIN_LIVE_MS = 5 * 60_000L // 开播至少 5 分钟后的标题变化才提醒
    }
}
