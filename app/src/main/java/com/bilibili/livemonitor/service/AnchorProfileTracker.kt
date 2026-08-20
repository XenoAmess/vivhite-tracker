package com.bilibili.livemonitor.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.bilibili.livemonitor.api.BilibiliApi
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MediaSnapshotEntity
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.AppUpdater
import com.bilibili.livemonitor.util.BiliTargets
import com.bilibili.livemonitor.util.MediaHistoryImporter
import com.bilibili.livemonitor.util.MediaStore
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Hourly avatar poller. A change is committed only after the new raw image is valid on disk. */
class AnchorProfileTracker(
    private val context: Context,
    private val prefs: PreferenceManager,
    private val scope: CoroutineScope,
    private val onAvatarChanged: (Bitmap) -> Unit
) {
    private val checking = AtomicBoolean(false)

    internal var faceUrlFetcher: suspend () -> String? = {
        BilibiliApi().fetchAnchorFace(BiliTargets.MONITOR_MID)
    }
    internal var mediaStore: MediaStore = MediaStore()

    fun maybeCheck(
        now: Long = System.currentTimeMillis(),
        isCurrent: () -> Boolean = { prefs.isServiceRunning() }
    ) {
        val elapsed = now - prefs.getLastAvatarCheckTime()
        if (elapsed in 0 until CHECK_INTERVAL_MS || !checking.compareAndSet(false, true)) return
        scope.launch {
            val previousCheckTime = prefs.getLastAvatarCheckTime()
            var attemptStarted = false
            try {
                if (!isCurrent()) return@launch
                // Genuine network failures keep the one-hour backoff; cancellation/stale generation restores it.
                prefs.setLastAvatarCheckTime(now)
                attemptStarted = true
                MediaHistoryImporter.ensureImported(context)
                if (!isCurrent()) return@launch
                val url = faceUrlFetcher() ?: return@launch
                syncAvatar(url, now, isCurrent)
            } catch (e: CancellationException) {
                if (attemptStarted) restoreAttemptIfOwned(now, previousCheckTime)
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "avatar check failed", e)
            } finally {
                if (attemptStarted && !isCurrent()) restoreAttemptIfOwned(now, previousCheckTime)
                checking.set(false)
            }
        }
    }

    internal suspend fun syncAvatar(
        url: String,
        now: Long,
        isCurrent: () -> Boolean = { true }
    ): Boolean {
        if (!isCurrent()) return false
        val dao = AppDatabase.get(context).mediaSnapshotDao()
        val previous = dao.latestSnapshot(MediaSnapshotEntity.KIND_AVATAR)
        val expectedKey = mediaStore.identityForUrl(url)?.contentKey
        val sameContent = expectedKey != null && previous?.contentKey == expectedKey

        val stored = if (sameContent) {
            val existing = previous?.let {
                File(context.filesDir, "avatars/${it.fileName}")
            }?.takeIf(mediaStore::isValidImage)
            if (existing != null) {
                MediaStore.StoredMedia(previous!!.contentKey, existing.name, existing)
            } else {
                mediaStore.acquire(context, MediaSnapshotEntity.KIND_AVATAR, url, isCurrent)
            }
        } else {
            mediaStore.acquire(context, MediaSnapshotEntity.KIND_AVATAR, url, isCurrent)
        } ?: return false

        if (!isCurrent()) return false
        publishCurrentAvatar(stored.file, isCurrent)
        if (!isCurrent()) return false
        val baselineWasInitialized = prefs.isAvatarBaselineInitialized()
        if (previous?.contentKey != stored.contentKey) {
            dao.insertSnapshot(
                MediaSnapshotEntity(
                    kind = MediaSnapshotEntity.KIND_AVATAR,
                    observedAt = now,
                    contentKey = stored.contentKey,
                    sourceUrl = url,
                    fileName = stored.fileName
                )
            )
            if (baselineWasInitialized && prefs.isNotifyAvatarChange() && isCurrent()) {
                decodeNotificationAvatar(stored.file)?.let(onAvatarChanged)
            }
        }
        if (isCurrent()) prefs.setAvatarBaselineInitialized(true)
        return previous?.contentKey != stored.contentKey
    }

    private fun publishCurrentAvatar(source: File, isCurrent: () -> Boolean) {
        val current = File(context.filesDir, "anchor_avatar.jpg")
        if (mediaStore.isValidImage(current) &&
            runCatching { mediaStore.sha1Hex(current) == mediaStore.sha1Hex(source) }.getOrDefault(false)
        ) {
            return
        }
        val temp = File.createTempFile(".${current.name}.", ".part", current.parentFile)
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    (output as? java.io.FileOutputStream)?.fd?.sync()
                }
            }
            if (isCurrent() && mediaStore.isValidImage(temp)) AppUpdater.publishAtomically(temp, current)
        } finally {
            temp.delete()
        }
    }

    private fun restoreAttemptIfOwned(attemptTime: Long, previousTime: Long) {
        if (prefs.getLastAvatarCheckTime() == attemptTime) prefs.setLastAvatarCheckTime(previousTime)
    }

    private fun decodeNotificationAvatar(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 256) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    companion object {
        internal const val CHECK_INTERVAL_MS = 60L * 60_000L
        private const val TAG = "AnchorProfileTracker"
    }
}
