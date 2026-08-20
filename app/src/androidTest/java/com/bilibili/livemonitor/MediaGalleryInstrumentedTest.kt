package com.bilibili.livemonitor

import android.graphics.Bitmap
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        AppDatabase.get(context).mediaSnapshotDao().deleteAll()
        File(context.filesDir, "avatars").deleteRecursively()
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
        Unit
    }

    @Test
    fun opensGalleryAndSelectsImageForBatchShare() {
        assertEquals(1, runBlocking {
            MediaGalleryRepository(context, AppDatabase.get(context)).load().size
        })
        ActivityScenario.launch(MediaGalleryActivity::class.java).use {
            onView(withId(R.id.chipGalleryAvatar)).check(matches(isDisplayed())).perform(click())
            Thread.sleep(1_500)
            onView(withId(R.id.ivGalleryImage)).perform(longClick())
            onView(withId(R.id.tvGallerySelectionCount)).check(matches(withText("已选 1 张")))
            onView(withId(R.id.btnGalleryShareSelected)).check(matches(isDisplayed()))
        }
    }
}
