package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StatsImageAssetLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `封面仅从应用封面目录采样解码并可统一回收`() = runBlocking {
        val allowed = File(context.filesDir, "covers/poster-asset.png").apply {
            parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(1_280, 720, Bitmap.Config.ARGB_8888)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val outside = File(context.cacheDir, "outside-cover.png").apply {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val data = sampleData(
            listOf(
                StatsImageRenderer.RecordLine(
                    StatsImageRenderer.RecordKind.SESSION,
                    "有效封面",
                    coverPaths = listOf(allowed.absolutePath)
                ),
                StatsImageRenderer.RecordLine(
                    StatsImageRenderer.RecordKind.SESSION,
                    "目录外封面",
                    coverPaths = listOf(outside.absolutePath)
                )
            )
        )

        val loaded = StatsImageAssetLoader.load(context, data)
        val bitmap = loaded.records[0].coverBitmaps.singleOrNull()
        assertNotNull(bitmap)
        assertTrue(maxOf(bitmap!!.width, bitmap.height) <= 640)
        assertTrue(loaded.records[1].coverBitmaps.isEmpty())

        StatsImageAssetLoader.recycle(loaded)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `单张月报最多预解码二十场封面`() = runBlocking {
        val allowed = File(context.filesDir, "covers/poster-limit.png").apply {
            parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val records = List(StatsImageAssetLoader.MAX_COVER_COUNT + 5) { index ->
            StatsImageRenderer.RecordLine(
                StatsImageRenderer.RecordKind.SESSION,
                "场次 $index",
                coverPaths = listOf(allowed.absolutePath)
            )
        }

        val loaded = StatsImageAssetLoader.load(context, sampleData(records))

        assertTrue(
            loaded.records.count { it.coverBitmaps.isNotEmpty() } == StatsImageAssetLoader.MAX_COVER_COUNT
        )
        StatsImageAssetLoader.recycle(loaded)
    }

    @Test
    fun `封面变化的场次解码前后两张并统一回收`() = runBlocking {
        fun writeCover(name: String, color: Int): File =
            File(context.filesDir, "covers/$name").apply {
                parentFile!!.mkdirs()
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        val first = writeCover("change-first.png", 0xFF6750A4.toInt())
        val second = writeCover("change-second.png", 0xFFF48FB1.toInt())
        val data = sampleData(
            listOf(
                StatsImageRenderer.RecordLine(
                    StatsImageRenderer.RecordKind.SESSION,
                    "封面变化场",
                    coverPaths = listOf(first.absolutePath, second.absolutePath)
                )
            )
        )

        val loaded = StatsImageAssetLoader.load(context, data)
        assertEquals(2, loaded.records[0].coverBitmaps.size)

        val bitmaps = loaded.records[0].coverBitmaps
        StatsImageAssetLoader.recycle(loaded)
        assertTrue(bitmaps.all { it.isRecycled })
    }

    private fun sampleData(records: List<StatsImageRenderer.RecordLine>) =
        StatsImageRenderer.StatsImageData(
            monthTitle = "2026年8月",
            summaryLines = listOf("本月 1 场"),
            barsTitle = "本月逐周场次",
            barCounts = listOf(1),
            barLabels = listOf("1-7"),
            leading = 0,
            daysInMonth = 31,
            sessionDays = setOf(1),
            magicDays = emptySet(),
            todayDom = 0,
            moodStats = emptyList(),
            magicSummary = null,
            records = records,
            exportDate = "2026-08-20"
        )
}
