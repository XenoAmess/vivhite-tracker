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
                data.copy(
                    records = data.records.map { record ->
                        if (record.kind != StatsImageRenderer.RecordKind.SESSION ||
                            record.coverPaths.isEmpty()
                        ) {
                            return@map record
                        }
                        // 整月每场都加载；每场取前两张有效封面，相同路径复用。
                        // 惰性解码，避免将不会展示的后续封面留在内存中。
                        val covers = record.coverPaths.asSequence().mapNotNull { path ->
                            decoded[path]?.let { return@mapNotNull it }
                            if (path in invalid) return@mapNotNull null
                            val file = runCatching { File(path).canonicalFile }.getOrNull()
                                ?: return@mapNotNull null
                            if (file.parentFile != coverRoot) {
                                invalid += path
                                return@mapNotNull null
                            }
                            decodeSampled(file)?.also {
                                decoded[path] = it
                                owned += it
                            } ?: run {
                                invalid += path
                                null
                            }
                        }.take(MAX_COVERS_PER_SESSION).toList()
                        record.copy(coverBitmaps = covers)
                    }
                )
            }
        } catch (failure: Throwable) {
            recycleBitmaps(owned)
            throw failure
        }
    }

    fun recycle(data: StatsImageRenderer.StatsImageData) {
        recycleBitmaps(data.records.flatMap { it.coverBitmaps })
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

    private const val MAX_COVERS_PER_SESSION = 2
    private const val MAX_COVER_DIMENSION = 640
}
