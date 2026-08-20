package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.MediaSnapshotEntity
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        File(context.filesDir, "avatars").deleteRecursively()
        File(context.filesDir, "covers").deleteRecursively()
    }

    @Test
    fun `BFS identity ignores CDN host and query`() {
        val hash = "718c36bc01a173d0c42c0d1131f67e31055244c7"
        val store = MediaStore()
        assertEquals(
            hash,
            store.identityForUrl("https://i0.hdslb.com/bfs/face/$hash.jpg@240w.jpg")?.contentKey
        )
        assertEquals(
            hash,
            store.identityForUrl("https://i2.hdslb.com/bfs/face/$hash.jpg?x=1")?.contentKey
        )
    }

    @Test
    fun `download verifies BFS sha1 and stores raw image once`() = runBlocking {
        val bytes = imageBytes(48, 48)
        val hash = sha1(bytes)
        var fetches = 0
        val store = MediaStore().apply { fetcher = { fetches++; bytes } }
        val url = "https://i1.hdslb.com/bfs/face/$hash.png"

        val first = store.acquire(context, MediaSnapshotEntity.KIND_AVATAR, url)
        val second = store.acquire(context, MediaSnapshotEntity.KIND_AVATAR, url)

        assertNotNull(first)
        assertEquals(hash, first!!.contentKey)
        assertTrue(first.file.isFile)
        assertEquals(1, fetches)
        assertEquals(first.file, second!!.file)
    }

    @Test
    fun `mismatched BFS content is rejected`() = runBlocking {
        val bytes = imageBytes(32, 32)
        val store = MediaStore().apply { fetcher = { bytes } }
        assertNull(
            store.acquire(
                context,
                MediaSnapshotEntity.KIND_AVATAR,
                "https://i1.hdslb.com/bfs/face/${"0".repeat(40)}.png"
            )
        )
    }

    private fun imageBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF6750A4.toInt())
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
