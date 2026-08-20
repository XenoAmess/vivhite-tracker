package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Loads sampled poster-only media off the main thread and owns the resulting bitmaps. */
object StatsImageAssetLoader {

    suspend fun load(
        context: Context,
        data: StatsImageRenderer.StatsImageData
    ): StatsImageRenderer.StatsImageData {
        val owned = mutableListOf<Bitmap>()
        return try {
            withContext(Dispatchers.IO) {
                val coverRoot = File(context.filesDir, "covers").canonicalFile
                val decoded = mutableMapOf<String, Bitmap>()
                val invalid = mutableSetOf<String>()
                var loadedCount = 0
                data.copy(
                    records = data.records.map { record ->
                        if (record.kind != StatsImageRenderer.RecordKind.SESSION ||
                            record.coverPaths.isEmpty() ||
                            loadedCount >= MAX_COVER_COUNT
                        ) {
                            return@map record
                        }
                        loadedCount++
                        val cover = record.coverPaths.firstNotNullOfOrNull { path ->
                            decoded[path]?.let { return@firstNotNullOfOrNull it }
                            if (path in invalid) return@firstNotNullOfOrNull null
                            val file = runCatching { File(path).canonicalFile }.getOrNull()
                                ?: return@firstNotNullOfOrNull null
                            if (file.parentFile != coverRoot) {
                                invalid += path
                                return@firstNotNullOfOrNull null
                            }
                            decodeSampled(file)?.also {
                                decoded[path] = it
                                owned += it
                            } ?: run {
                                invalid += path
                                null
                            }
                        }
                        record.copy(coverBitmap = cover)
                    }
                )
            }
        } catch (failure: Throwable) {
            recycleBitmaps(owned)
            throw failure
        }
    }

    fun recycle(data: StatsImageRenderer.StatsImageData) {
        recycleBitmaps(data.records.mapNotNull { it.coverBitmap })
    }

    private fun recycleBitmaps(bitmaps: Iterable<Bitmap>) {
        bitmaps.distinctBy { System.identityHashCode(it) }.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    internal fun decodeSampled(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_COVER_DIMENSION) {
            sample *= 2
        }
        return runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }

    internal const val MAX_COVER_COUNT = 21
    private const val MAX_COVER_DIMENSION = 640
}
