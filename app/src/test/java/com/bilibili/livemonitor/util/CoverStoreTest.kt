package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var coversDir: File

    @Before
    fun setUp() {
        coversDir = File(context.filesDir, "covers")
        coversDir.deleteRecursively()
    }

    private fun storeWith(bytes: ByteArray?): Pair<CoverStore, IntArray> {
        val callCount = intArrayOf(0)
        val store = CoverStore()
        store.fetcher = { callCount[0]++; bytes }
        return store to callCount
    }

    private fun imageBytes(color: Int = Color.MAGENTA): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Test
    fun `同 URL 二次 acquire 去重短路 字节一致`() = runBlocking {
        val original = imageBytes()
        val (store, calls) = storeWith(original)
        val p1 = store.acquire(context, "https://i0.hdslb.com/cover/a.jpg")
        val p2 = store.acquire(context, "https://i0.hdslb.com/cover/a.jpg")
        assertNotNull(p1)
        assertEquals("同 URL 同路径", p1, p2)
        assertEquals("第二次不得再下载", 1, calls[0])
        assertEquals(1, coversDir.listFiles()!!.size)
        assertTrue("原图字节保真", File(p1!!).readBytes().contentEquals(original))
    }

    @Test
    fun `不同 URL 两个文件`() = runBlocking {
        val (store, _) = storeWith(imageBytes())
        val p1 = store.acquire(context, "https://i0.hdslb.com/cover/a.jpg")
        val p2 = store.acquire(context, "https://i0.hdslb.com/cover/b.jpg")
        assertNotNull(p1); assertNotNull(p2)
        assertTrue(p1 != p2)
        assertEquals(2, coversDir.listFiles()!!.size)
    }

    @Test
    fun `下载失败返回 null 不留半成品`() = runBlocking {
        val (store, _) = storeWith(null)
        assertNull(store.acquire(context, "https://i0.hdslb.com/cover/x.jpg"))
        assertEquals(0, coversDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `非法图片不发布且临时文件被清理`() = runBlocking {
        val (store, _) = storeWith("not-an-image".toByteArray())

        assertNull(store.acquire(context, "https://i0.hdslb.com/cover/broken.jpg"))
        assertTrue(coversDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertEquals(0, coversDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `损坏的已有缓存不会被接受并会重新下载`() = runBlocking {
        val url = "https://i0.hdslb.com/cover/recover.jpg"
        val (store, calls) = storeWith(imageBytes(Color.CYAN))
        val cache = store.fileFor(context, url)
        cache.parentFile?.mkdirs()
        cache.writeText("partial")

        val result = store.acquire(context, url)

        assertNotNull(result)
        assertEquals(1, calls[0])
        assertTrue(cache.length() > "partial".length)
    }
}
