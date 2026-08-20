package com.bilibili.livemonitor.service

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.util.MediaStore
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AnchorProfileTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PreferenceManager
    private lateinit var tracker: AnchorProfileTracker
    private var alerts = 0

    @Before
    fun setUp() = runBlocking {
        File(context.filesDir, "avatars").deleteRecursively()
        File(context.filesDir, "anchor_avatar.jpg").delete()
        AppDatabase.get(context).mediaSnapshotDao().deleteAll()
        prefs = PreferenceManager(context)
        prefs.setAvatarBaselineInitialized(false)
        prefs.setNotifyAvatarChange(true)
        alerts = 0
        tracker = AnchorProfileTracker(context, prefs, scope) { bitmap ->
            alerts++
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `first baseline is silent then changed URL alerts once`() = runBlocking {
        val first = imageBytes(0xFF6750A4.toInt())
        val second = imageBytes(0xFFFF6699.toInt())
        val bytesByUrl = listOf(first, second).associateBy { bytes -> urlFor(bytes) }
        tracker.mediaStore = MediaStore().apply { fetcher = { bytesByUrl[it] } }

        assertTrue(tracker.syncAvatar(urlFor(first), 1_000L))
        assertEquals(0, alerts)
        assertEquals(1, AppDatabase.get(context).mediaSnapshotDao().allSnapshots().size)
        assertTrue(File(context.filesDir, "anchor_avatar.jpg").isFile)

        assertFalse(tracker.syncAvatar(urlFor(first), 2_000L))
        assertEquals(0, alerts)

        assertTrue(tracker.syncAvatar(urlFor(second), 3_000L))
        assertEquals(1, alerts)
        assertEquals(2, AppDatabase.get(context).mediaSnapshotDao().allSnapshots().size)
    }

    @Test
    fun `failed download does not advance baseline`() = runBlocking {
        tracker.mediaStore = MediaStore().apply { fetcher = { null } }
        assertFalse(tracker.syncAvatar("https://i1.hdslb.com/bfs/face/${"a".repeat(40)}.jpg", 1_000L))
        assertFalse(prefs.isAvatarBaselineInitialized())
        assertTrue(AppDatabase.get(context).mediaSnapshotDao().allSnapshots().isEmpty())
    }

    @Test
    fun `future timestamp does not make changed avatar alert repeatedly`() = runBlocking {
        val first = imageBytes(0xFF6750A4.toInt())
        val second = imageBytes(0xFFFF6699.toInt())
        val bytesByUrl = listOf(first, second).associateBy(::urlFor)
        tracker.mediaStore = MediaStore().apply { fetcher = { bytesByUrl[it] } }
        prefs.setAvatarBaselineInitialized(false)
        tracker.syncAvatar(urlFor(first), Long.MAX_VALUE / 2)

        tracker.syncAvatar(urlFor(second), 2_000L)
        tracker.syncAvatar(urlFor(second), 3_000L)

        assertEquals(1, alerts)
        assertEquals(2, AppDatabase.get(context).mediaSnapshotDao().allSnapshots().size)
    }

    @Test
    fun `stale generation after fetch does not publish or record`() = runBlocking {
        val bytes = imageBytes(0xFF6750A4.toInt())
        var current = true
        tracker.mediaStore = MediaStore().apply {
            fetcher = {
                current = false
                bytes
            }
        }

        assertFalse(tracker.syncAvatar(urlFor(bytes), 1_000L) { current })
        assertTrue(AppDatabase.get(context).mediaSnapshotDao().allSnapshots().isEmpty())
        assertTrue(File(context.filesDir, "avatars").listFiles().orEmpty().isEmpty())
    }

    private fun imageBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun urlFor(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return "https://i1.hdslb.com/bfs/face/$hash.png"
    }
}
