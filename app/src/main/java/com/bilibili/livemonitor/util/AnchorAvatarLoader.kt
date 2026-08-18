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
    internal open var bitmapDownloader: (String) -> Bitmap? = { url ->
        ShareImageLoader().downloadBitmap(url)
    }

    open suspend fun load(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        val cache = cacheFile(context)
        val cached = readCache(cache)
        if (cached != null &&
            System.currentTimeMillis() - cache.lastModified() < TTL_MS
        ) {
            return@withContext cached
        }
        // 过期/缺失 → 网络刷新
        val fresh = faceUrlFetcher()?.let { url -> bitmapDownloader(url) }
        if (fresh != null) {
            cache.parentFile?.mkdirs()
            val temp = File.createTempFile(".${cache.name}.", ".part", cache.parentFile)
            try {
                val encoded = runCatching {
                    temp.outputStream().use { output ->
                        val compressed = fresh.compress(Bitmap.CompressFormat.JPEG, 92, output)
                        output.flush()
                        (output as? java.io.FileOutputStream)?.fd?.sync()
                        compressed
                    }
                }.getOrDefault(false)
                val decoded = if (encoded) readCache(temp, deleteInvalid = false) else null
                val valid = decoded != null
                decoded?.recycle()
                if (valid) AppUpdater.publishAtomically(temp, cache)
            } finally {
                temp.delete()
            }
            return@withContext fresh
        }
        // 网络失败回退旧缓存（哪怕过期），全空 null
        cached
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
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bitmap == null && deleteInvalid) file.delete()
        return bitmap
    }

    companion object {
        internal const val TTL_MS = 24L * 3600 * 1000
    }
}
