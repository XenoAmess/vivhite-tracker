package com.bilibili.livemonitor.service

import android.content.Context
import androidx.room.withTransaction
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.PopularityPointEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.db.StreamTitleChangeEntity
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
    private val sessionTasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in sessionTasks) task()
        }
    }

    private fun enqueueSessionTask(name: String, task: suspend () -> Unit) {
        if (sessionTasks.trySend {
                try {
                    task()
                } catch (e: Exception) {
                    AppLogger.w(tag, "$name failed", e)
                }
            }.isFailure
        ) {
            AppLogger.w(tag, "$name skipped: tracker scope is closed")
        }
    }

    // 标题变化始终落库；通知开关与开播 5 分钟门槛只控制是否提醒。
    fun trackTitleChange(liveTitle: String?) {
        if (liveTitle.isNullOrBlank()) return
        val lastTitle = prefs.getLastLiveTitle()
        if (liveTitle == lastTitle) return
        prefs.setLastLiveTitle(liveTitle)
        if (lastTitle.isBlank()) return
        val now = System.currentTimeMillis()
        val startTs = parseLiveStartTime(prefs.getLastLiveStartTime())
        if (prefs.isNotifyTitleChange() && startTs != null && now - startTs >= TITLE_CHANGE_MIN_LIVE_MS) {
            onTitleChange(liveTitle)
        }
        val dao = AppDatabase.get(context).streamSessionDao()
        enqueueSessionTask("record title change") {
            dao.findOpenSession()?.let { open ->
                dao.insertTitleChange(
                    StreamTitleChangeEntity(
                        sessionId = open.id,
                        changedAt = now,
                        oldTitle = lastTitle,
                        newTitle = liveTitle
                    )
                )
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
        if (!liveTitle.isNullOrBlank()) prefs.setLastLiveTitle(liveTitle)
        enqueueSessionTask("record stream start") {
            val id = dao.beginSession(startTs, liveTitle)
            AppLogger.d(tag, "record stream start: active session $id")
        }
    }

    // 下播跳变：闭合开在场次；无开在场次（进程死亡后）用 prefs 的 live_start_time 补
    fun recordStreamEnd() {
        val dao = AppDatabase.get(context).streamSessionDao()
        val endTs = System.currentTimeMillis()
        val title = prefs.getLastLiveTitle()
        enqueueSessionTask("record stream end") {
            val openStart = dao.endOpenSessions(endTs, title)
            val startTs = openStart ?: parseLiveStartTime(prefs.getLastLiveStartTime())
            if (openStart == null && startTs != null) {
                dao.insertSession(
                    StreamSessionEntity(startTs = startTs, endTs = maxOf(startTs, endTs), title = title)
                )
            }
            if (startTs != null && prefs.isNotifyStreamEnd()) {
                onStreamEnd(maxOf(0L, endTs - startTs))
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
        enqueueSessionTask("reconcile open session") {
            val open = dao.findOpenSession() ?: return@enqueueSessionTask
            val observed = prefs.getLastLiveObservedTime()
            val endTs = reconcileEndTs(open.startTs, observed)
            dao.closeOpenSessions(endTs, prefs.getLastLiveTitle())
            AppLogger.d(tag, "reconciled stale open session ${open.id}, endTs=$endTs")
        }
    }

    internal fun reconcileEndTs(openStartTs: Long, lastLiveObservedMs: Long): Long =
        if (lastLiveObservedMs >= openStartTs) lastLiveObservedMs else openStartTs

    /**
     * 人气采样（每次 Live 轮询调用）：online 为 null（网页兜底路径拿不到）或
     * 无开放场次（进程死亡恢复中）时跳过，不产生孤儿点
     */
    fun recordPopularity(online: Int?) {
        if (online == null) return
        val dao = AppDatabase.get(context).streamSessionDao()
        enqueueSessionTask("record popularity") {
            dao.findOpenSession()?.let { open ->
                dao.insertPopularityPoint(
                    PopularityPointEntity(
                        sessionId = open.id,
                        ts = System.currentTimeMillis(),
                        online = online
                    )
                )
            }
        }
    }

    // internal seams：单测注入假封面源/假存储
    internal var coverUrlFetcher: suspend (Long) -> String? = { roomId ->
        com.bilibili.livemonitor.api.BilibiliApi().fetchRoomInfo(roomId)?.cover
    }
    internal var coverStore: com.bilibili.livemonitor.util.CoverStore =
        com.bilibili.livemonitor.util.CoverStore()
    internal var mediaStore: com.bilibili.livemonitor.util.MediaStore =
        com.bilibili.livemonitor.util.MediaStore()
    // 粉丝数源（Master/info follower_num），单测注入假数据
    internal var followerNumFetcher: suspend (Long) -> Long? = { mid ->
        com.bilibili.livemonitor.api.BilibiliApi().fetchFollowerNum(mid)
    }

    /** 粉丝数每日快照：距上次满 20h 才采（FollowerDecider 天闸），失败静默 */
    fun maybeSnapshotFollower(now: Long = System.currentTimeMillis()) {
        scope.launch {
            try {
                val dao = AppDatabase.get(context).streamSessionDao()
                val lastTs = dao.lastFollowerSnapshotTs()
                if (!com.bilibili.livemonitor.domain.FollowerDecider.shouldSnapshot(lastTs, now)) {
                    return@launch
                }
                val num = followerNumFetcher(com.bilibili.livemonitor.util.BiliTargets.MONITOR_MID)
                    ?: return@launch
                dao.insertFollowerSnapshot(
                    com.bilibili.livemonitor.db.FollowerSnapshotEntity(ts = now, followerNum = num)
                )
            } catch (e: Exception) {
                AppLogger.w(tag, "snapshot follower failed", e)
            }
        }
    }

    /**
     * 开播封面收藏（幂等）：当前开放场次没有 cover_path 才拉一次；
     * 存储层按 URL sha256 去重（同封面零重复下载），失败静默
     */
    fun collectStreamCover(roomId: Long) {
        scope.launch {
            try {
                val dao = AppDatabase.get(context).streamSessionDao()
                val open = dao.findOpenSession() ?: return@launch
                if (!open.coverPath.isNullOrBlank()) return@launch
                val url = coverUrlFetcher(roomId) ?: return@launch
                val path = coverStore.acquire(context, url) ?: return@launch
                dao.setCoverIfMissing(open.id, path)
            } catch (e: Exception) {
                AppLogger.w(tag, "collect stream cover failed", e)
            }
        }
    }

    /**
     * 直播状态响应自带 user_cover：同场每次内容键变化都收藏，无额外 API 请求。
     * 场次首图继续写 cover_path，后续变化只进媒体时间线。
     */
    fun collectStreamCover(
        coverUrl: String?,
        liveStartTime: String?,
        liveTitle: String?,
        isCurrent: () -> Boolean = { true }
    ) {
        if (coverUrl.isNullOrBlank()) return
        enqueueSessionTask("collect stream cover history") {
            if (!isCurrent()) return@enqueueSessionTask
            val database = AppDatabase.get(context)
            val sessionDao = database.streamSessionDao()
            val open = sessionDao.findOpenSession() ?: return@enqueueSessionTask
            val expectedStart = parseLiveStartTime(liveStartTime)
            if (expectedStart != null && open.startTs != expectedStart) return@enqueueSessionTask

            val mediaDao = database.mediaSnapshotDao()
            val identity = mediaStore.identityForUrl(coverUrl)
            val previous = mediaDao.latestForSession(
                com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_ROOM_COVER,
                open.startTs
            )
            if (identity != null && previous?.contentKey == identity.contentKey) {
                val existing = java.io.File(context.filesDir, "covers/${previous.fileName}")
                if (mediaStore.isValidImage(existing)) {
                    val sessionFile = open.coverPath?.let { java.io.File(it) }
                    if (sessionFile == null || !mediaStore.isValidImage(sessionFile)) {
                        sessionDao.setCoverPath(open.id, existing.absolutePath)
                    }
                    return@enqueueSessionTask
                }
            }

            val stored = mediaStore.acquire(
                context,
                com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_ROOM_COVER,
                coverUrl,
                isCurrent
            ) ?: return@enqueueSessionTask
            if (!isCurrent()) return@enqueueSessionTask
            if (previous?.contentKey == stored.contentKey) {
                database.withTransaction {
                    mediaDao.updateFileName(
                        com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_ROOM_COVER,
                        stored.contentKey,
                        stored.fileName
                    )
                    val sessionFile = open.coverPath?.let { java.io.File(it) }
                    if (sessionFile == null || !mediaStore.isValidImage(sessionFile)) {
                        sessionDao.setCoverPath(open.id, stored.file.absolutePath)
                    }
                }
                return@enqueueSessionTask
            }
            database.withTransaction {
                if (!isCurrent()) return@withTransaction
                mediaDao.insertSnapshot(
                    com.bilibili.livemonitor.db.MediaSnapshotEntity(
                        kind = com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_ROOM_COVER,
                        observedAt = System.currentTimeMillis(),
                        contentKey = stored.contentKey,
                        sourceUrl = coverUrl,
                        fileName = stored.fileName,
                        sessionStartTs = open.startTs,
                        title = liveTitle?.takeIf { it.isNotBlank() } ?: open.title
                    )
                )
                sessionDao.setCoverIfMissing(open.id, stored.file.absolutePath)
            }
        }
    }

    private companion object {
        const val TITLE_CHANGE_MIN_LIVE_MS = 5 * 60_000L // 开播至少 5 分钟后的标题变化才提醒
    }
}
