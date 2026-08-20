package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * 主播头像加载（场次页左上角 + 导出海报共用）：
 * 磁盘缓存 filesDir/anchor_avatar.jpg，TTL 24h——
 * 新鲜直读 / 过期走网络刷新并写缓存 / 网络失败回退旧缓存（哪怕过期）/ 全空返回 null。
 * fetcher/downloader 为 internal seam 供单测注入。
 */
open class AnchorAvatarLoader {

    internal open var faceUrlFetcher: suspend () -> String? = {
        com.bilibili.livemonitor.api.BilibiliApi().fetchAnchorFace(BiliTargets.MONITOR_MID)
    }
    internal open var mediaStore: MediaStore = MediaStore()

    open suspend fun load(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        val cache = cacheFile(context)
        val cached = readCache(cache)
        if (cached != null &&
            System.currentTimeMillis() - cache.lastModified() < TTL_MS
        ) {
            return@withContext cached
        }
        // 过期/缺失 → 网络刷新
        val url = faceUrlFetcher()
        val stored = url?.let {
            mediaStore.acquire(context, com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_AVATAR, it)
        }
        if (stored != null) {
            val dao = com.bilibili.livemonitor.db.AppDatabase.get(context).mediaSnapshotDao()
            if (dao.latestSnapshot(com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_AVATAR)
                    ?.contentKey != stored.contentKey
            ) {
                dao.insertSnapshot(
                    com.bilibili.livemonitor.db.MediaSnapshotEntity(
                        kind = com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_AVATAR,
                        observedAt = System.currentTimeMillis(),
                        contentKey = stored.contentKey,
                        sourceUrl = url,
                        fileName = stored.fileName
                    )
                )
            }
            PreferenceManager(context).setAvatarBaselineInitialized(true)
            publishCurrent(stored.file, cache)
            return@withContext readCache(stored.file, deleteInvalid = false)
        }
        // 网络失败回退旧缓存（哪怕过期），全空 null
        cached
    }

    /** 历史月份使用月末前最后发现的头像；追踪开始前的月份回退最早留存头像。 */
    open suspend fun loadForMonth(context: Context, month: Calendar): Bitmap? =
        withContext(Dispatchers.IO) {
            MediaHistoryImporter.ensureImported(context)
            val monthStart = (month.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val monthEnd = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
            val target = minOf(monthEnd - 1, System.currentTimeMillis())
            val dao = com.bilibili.livemonitor.db.AppDatabase.get(context).mediaSnapshotDao()
            val kind = com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_AVATAR
            val candidates = dao.snapshotsAtOrBefore(kind, target) +
                dao.snapshotsOldestFirst(kind)
            val historical = candidates.distinctBy { it.id }.firstNotNullOfOrNull {
                readCache(File(File(context.filesDir, "avatars"), it.fileName), deleteInvalid = false)
            }
            when {
                historical != null -> historical
                monthEnd <= System.currentTimeMillis() -> null
                else -> load(context)
            }
        }

    /** 圆形裁切（页面 ImageView 与海报共用；输出边长 = min(宽,高) 的正方形） */
    fun cropCircle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        c.clipPath(Path().apply { addRoundRect(rect, size / 2f, size / 2f, Path.Direction.CW) })
        val left = (src.width - size) / 2
        val top = (src.height - size) / 2
        c.drawBitmap(src, android.graphics.Rect(left, top, left + size, top + size), rect, null)
        return out
    }

    /** 占位头像：浅紫圆底 + 紫「白」字（网络与缓存都空时页面用） */
    fun placeholder(size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF3EFFC.toInt()
        })
        c.drawText("白", size / 2f, size / 2f + size * 0.14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6750A4.toInt()
            textSize = size * 0.42f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        })
        return out
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, "anchor_avatar.jpg")

    private fun readCache(file: File, deleteInvalid: Boolean = true): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DECODE_DIMENSION) {
            sample *= 2
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
        if (bitmap == null && deleteInvalid) file.delete()
        return bitmap
    }

    private fun publishCurrent(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File.createTempFile(".${destination.name}.", ".part", destination.parentFile)
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    (output as? java.io.FileOutputStream)?.fd?.sync()
                }
            }
            if (mediaStore.isValidImage(temp)) AppUpdater.publishAtomically(temp, destination)
        } finally {
            temp.delete()
        }
    }

    companion object {
        internal const val TTL_MS = 24L * 3600 * 1000
        private const val MAX_DECODE_DIMENSION = 1440
    }
}
