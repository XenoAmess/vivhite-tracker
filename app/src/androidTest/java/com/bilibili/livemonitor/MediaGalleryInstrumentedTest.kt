package com.bilibili.livemonitor

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MediaSnapshotEntity
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaGalleryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() = runBlocking {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(LiveMonitorApp.NOTIFICATION_ID_ALERT)
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        UiDevice.getInstance(instrumentation).apply {
            pressHome()
            waitForIdle()
        }
        AppDatabase.get(context).mediaSnapshotDao().deleteAll()
        File(context.filesDir, "avatars").deleteRecursively()
        File(context.filesDir, "covers").deleteRecursively()
        val file = File(context.filesDir, "avatars/test-avatar.png")
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF6750A4.toInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        AppDatabase.get(context).mediaSnapshotDao().insertSnapshot(
            MediaSnapshotEntity(
                kind = MediaSnapshotEntity.KIND_AVATAR,
                observedAt = System.currentTimeMillis(),
                contentKey = "test-avatar",
                fileName = file.name
            )
        )
        PreferenceManager(context).setLegacyMediaImported(true)
    }

    @After
    fun tearDown() = runBlocking {
        AppDatabase.get(context).mediaSnapshotDao().deleteAll()
        File(context.filesDir, "avatars").deleteRecursively()
        File(context.filesDir, "covers").deleteRecursively()
        Unit
    }

    @Test
    fun opensGalleryAndSelectsImageForBatchShare() {
        assertEquals(1, runBlocking {
            MediaGalleryRepository(context, AppDatabase.get(context)).load().size
        })
        ActivityScenario.launch(MediaGalleryActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.findViewById<android.view.View>(R.id.chipGalleryAvatar).performClick()
            }
            waitFor("gallery item") {
                var selected = false
                scenario.onActivity { activity ->
                    val holder = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                        R.id.rvMediaGallery
                    ).findViewHolderForAdapterPosition(0)
                    if (holder != null) selected = holder.itemView.performLongClick()
                }
                selected
            }
            scenario.onActivity { activity ->
                assertEquals(
                    "已选 1 张",
                    activity.findViewById<android.widget.TextView>(R.id.tvGallerySelectionCount).text.toString()
                )
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.btnGalleryShareSelected).visibility
                )
            }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout: $what")
            Thread.sleep(100)
        }
    }
}
