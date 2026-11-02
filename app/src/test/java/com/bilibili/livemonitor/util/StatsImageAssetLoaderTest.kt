package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    fun `二十七场月报的相同封面全部加载且复用同一位图`() = runBlocking {
        val allowed = File(context.filesDir, "covers/poster-limit.png").apply {
            parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val records = List(27) { index ->
            StatsImageRenderer.RecordLine(
                StatsImageRenderer.RecordKind.SESSION,
                "场次 $index",
                coverPaths = listOf(allowed.absolutePath)
            )
        }

        val loaded = StatsImageAssetLoader.load(context, sampleData(records))

        assertEquals(27, loaded.records.count { it.coverBitmaps.isNotEmpty() })
        val bitmap = loaded.records.first().coverBitmaps.single()
        loaded.records.forEach { assertSame(bitmap, it.coverBitmaps.single()) }
        StatsImageAssetLoader.recycle(loaded)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `整月三十一场各有不同的前后封面均可加载回收`() = runBlocking {
        val records = List(31) { index ->
            val paths = List(2) { change ->
                File(context.filesDir, "covers/month-$index-$change.png").apply {
                    parentFile!!.mkdirs()
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }.absolutePath
            }
            StatsImageRenderer.RecordLine(
                StatsImageRenderer.RecordKind.SESSION,
                "场次 $index",
                coverPaths = paths
            )
        }

        val loaded = StatsImageAssetLoader.load(context, sampleData(records))
        val bitmaps = loaded.records.flatMap { it.coverBitmaps }

        assertEquals(31, loaded.records.size)
        assertTrue(loaded.records.all { it.coverBitmaps.size == 2 })
        assertEquals(62, bitmaps.distinct().size)
        StatsImageAssetLoader.recycle(loaded)
        assertTrue(bitmaps.all { it.isRecycled })
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

    @Test
    fun `跳过丢失损坏封面后仅保留前两张有效封面`() = runBlocking {
        val missing = File(context.filesDir, "covers/missing.png")
        val corrupt = File(context.filesDir, "covers/corrupt.png").apply {
            parentFile!!.mkdirs()
            writeText("not an image")
        }
        val validPaths = List(3) { index ->
            File(context.filesDir, "covers/fallback-$index.png").apply {
                val bitmap = Bitmap.createBitmap(64 * (index + 1), 64, Bitmap.Config.ARGB_8888)
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }.absolutePath
        }
        val record = StatsImageRenderer.RecordLine(
            StatsImageRenderer.RecordKind.SESSION,
            "多次封面变化",
            coverPaths = listOf(missing.absolutePath, corrupt.absolutePath) + validPaths
        )

        val loaded = StatsImageAssetLoader.load(context, sampleData(listOf(record)))
        val bitmaps = loaded.records.single().coverBitmaps

        assertEquals(listOf(64, 128), bitmaps.map { it.width })
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
