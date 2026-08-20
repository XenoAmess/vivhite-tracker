package com.bilibili.livemonitor

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaGalleryRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = runBlocking {
        cleanMediaRoots()
        File(context.filesDir, "anchor_avatar.jpg").delete()
        AppDatabase.get(context).mediaSnapshotDao().deleteAll()
        com.bilibili.livemonitor.util.PreferenceManager(context).setLegacyMediaImported(false)
    }

    @After
    fun cleanMediaRoots() {
        File(context.filesDir, "covers").deleteRecursively()
        File(context.filesDir, "avatars").deleteRecursively()
    }

    @Test
    fun `valid unindexed cover and avatar files are visible as legacy media`() = runBlocking {
        cleanMediaRoots()
        writeImage(File(context.filesDir, "covers/old-cover.bin"), 64, 36)
        writeImage(File(context.filesDir, "avatars/old-avatar.bin"), 32, 32)
        File(context.filesDir, "covers/not-an-image.jpg").writeText("broken")

        val items = MediaGalleryRepository(context, AppDatabase.get(context)).load()
            .filter { it.legacyOrphan }

        assertEquals(2, items.size)
        assertEquals(setOf("avatar", "room_cover"), items.mapTo(hashSetOf()) { it.kind })
        assertTrue(items.all { it.usageSummary.contains("旧文件") })
    }

    @Test
    fun `sampled decoder rejects corrupt files and bounds large images`() {
        val directory = File(context.filesDir, "covers").apply { mkdirs() }
        val image = File(directory, "large.png")
        writeImage(image, 1200, 800)
        val corrupt = File(directory, "bad.png").apply { writeText("not png") }

        val decoded = MediaGalleryActivity.decodeSampled(image, 128)

        assertTrue(decoded != null && maxOf(decoded.width, decoded.height) <= 256)
        assertEquals(null, MediaGalleryActivity.decodeSampled(corrupt, 128))
    }

    private fun writeImage(file: File, width: Int, height: Int) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF6750A4.toInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
