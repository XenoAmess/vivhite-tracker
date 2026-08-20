package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnchorAvatarLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cacheFile() = File(context.filesDir, "anchor_avatar.jpg")

    private fun fakeBitmap(): Bitmap =
        Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888) // 非方形验证 cropCircle

    @Before
    fun setUp() {
        cacheFile().delete()
        File(context.filesDir, "avatars").deleteRecursively()
        runBlocking { com.bilibili.livemonitor.db.AppDatabase.get(context).mediaSnapshotDao().deleteAll() }
        PreferenceManager(context).setLegacyMediaImported(true)
        context.filesDir.listFiles { file ->
            file.name.startsWith(".${cacheFile().name}.") && file.name.endsWith(".part")
        }?.forEach(File::delete)
    }

    private fun loaderWith(
        fetcher: suspend () -> String?,
        downloader: (String) -> Bitmap?
    ): AnchorAvatarLoader {
        val loader = AnchorAvatarLoader()
        loader.faceUrlFetcher = fetcher
        loader.mediaStore = object : MediaStore() {
            override suspend fun acquire(
                context: Context,
                kind: String,
                url: String,
                isCurrent: () -> Boolean
            ): StoredMedia? {
                val bitmap = downloader(url) ?: return null
                val file = File(context.filesDir, "avatars/fake.png")
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return StoredMedia("fake", file.name, file)
            }
        }
        return loader
    }

    @Test
    fun `无缓存走网络成功并写缓存`() = runBlocking {
        var fetchCount = 0
        val loader = loaderWith(
            { fetchCount++; "https://i1.hdslb.com/face.jpg" },
            { fakeBitmap() }
        )
        val bmp = loader.load(context)
        assertNotNull(bmp)
        assertEquals(1, fetchCount)
        assertTrue("成功后应写磁盘缓存", cacheFile().exists() && cacheFile().length() > 0)
        assertEquals(
            1,
            com.bilibili.livemonitor.db.AppDatabase.get(context).mediaSnapshotDao().allSnapshots().size
        )

        // 新鲜缓存：二次加载不再走网络
        val again = loaderWith(
            { fetchCount++; null },
            { null }
        ).load(context)
        assertNotNull(again)
        assertEquals("新鲜缓存不再打网络", 1, fetchCount)
    }

    @Test
    fun `网络失败回退过期缓存`() = runBlocking {
        // 预置过期缓存（mtime 拨到 25h 前）
        cacheFile().outputStream().use {
            fakeBitmap().compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        cacheFile().setLastModified(System.currentTimeMillis() - 25 * 3600_000L)

        val loader = loaderWith({ "https://i1.hdslb.com/face.jpg" }, { null })
        val bmp = loader.load(context)
        assertNotNull("网络失败应回退旧缓存", bmp)
    }

    @Test
    fun `无缓存且网络失败 返回null`() = runBlocking {
        val loader = loaderWith({ null }, { null })
        assertNull(loader.load(context))
    }

    @Test
    fun `损坏缓存不会短路网络且刷新不留临时文件`() = runBlocking {
        cacheFile().writeText("partial-avatar")
        var fetched = false
        val loader = loaderWith(
            { fetched = true; "https://i1.hdslb.com/fresh.jpg" },
            { fakeBitmap() }
        )

        assertNotNull(loader.load(context))
        assertTrue(fetched)
        assertNotNull(android.graphics.BitmapFactory.decodeFile(cacheFile().absolutePath))
        assertTrue(context.filesDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `cropCircle 输出正方形且尺寸取短边`() {
        val out = AnchorAvatarLoader().cropCircle(fakeBitmap())
        assertEquals(32, out.width)
        assertEquals(32, out.height)
    }

    @Test
    fun `placeholder 输出指定尺寸`() {
        val out = AnchorAvatarLoader().placeholder(96)
        assertEquals(96, out.width)
        assertEquals(96, out.height)
    }

    @Test
    fun `历史月份使用月末前最后发现的头像`() = runBlocking {
        val directory = File(context.filesDir, "avatars").apply { mkdirs() }
        val january = File(directory, "jan.png")
        val february = File(directory, "feb.png")
        writeColor(january, 0xFFFF0000.toInt())
        writeColor(february, 0xFF0000FF.toInt())
        val dao = com.bilibili.livemonitor.db.AppDatabase.get(context).mediaSnapshotDao()
        val janTs = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 10) }.timeInMillis
        val febTs = Calendar.getInstance().apply { clear(); set(2026, Calendar.FEBRUARY, 10) }.timeInMillis
        dao.insertSnapshot(
            com.bilibili.livemonitor.db.MediaSnapshotEntity(
                kind = com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_AVATAR,
                observedAt = janTs,
                contentKey = "jan",
                fileName = january.name
            )
        )
        dao.insertSnapshot(
            com.bilibili.livemonitor.db.MediaSnapshotEntity(
                kind = com.bilibili.livemonitor.db.MediaSnapshotEntity.KIND_AVATAR,
                observedAt = febTs,
                contentKey = "feb",
                fileName = february.name
            )
        )

        val month = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 1) }
        val loaded = AnchorAvatarLoader().loadForMonth(context, month)!!
        assertEquals(0xFFFF0000.toInt(), loaded.getPixel(4, 4))
    }

    private fun writeColor(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
