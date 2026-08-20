package com.bilibili.livemonitor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareImageFactoryBatchTest {

    @Test
    fun `batch share grants every stream through clip data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uris = listOf(
            Uri.parse("content://com.bilibili.livemonitor.fileprovider/gallery_covers/a.jpg"),
            Uri.parse("content://com.bilibili.livemonitor.fileprovider/gallery_avatars/b.png"),
            Uri.parse("content://com.bilibili.livemonitor.fileprovider/gallery_covers/c.webp")
        )

        val intent = ShareImageFactory.buildMultipleImageShareIntent(
            uris = uris,
            contentResolver = context.contentResolver,
            clipLabel = "gallery"
        )

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uris, intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uris.size, intent.clipData!!.itemCount)
        assertEquals(uris, (0 until intent.clipData!!.itemCount).map { intent.clipData!!.getItemAt(it).uri })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `batch share rejects an empty stream list`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShareImageFactory.buildMultipleImageShareIntent(
            uris = emptyList(),
            contentResolver = context.contentResolver,
            clipLabel = "gallery"
        )
    }
}
