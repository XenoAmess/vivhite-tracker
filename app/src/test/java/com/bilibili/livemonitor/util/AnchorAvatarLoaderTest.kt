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
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AnchorAvatarLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cacheFile() = File(context.filesDir, "anchor_avatar.jpg")

    private fun fakeBitmap(): Bitmap =
        Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888) // 非方形验证 cropCircle

    @Before
    fun setUp() {
        cacheFile().delete()
    }

    private fun loaderWith(
        fetcher: suspend () -> String?,
        downloader: (String) -> Bitmap?
    ): AnchorAvatarLoader {
        val loader = AnchorAvatarLoader()
        loader.faceUrlFetcher = fetcher
        loader.bitmapDownloader = downloader
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
}
