package com.bilibili.livemonitor.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.LiveMonitorApp
import com.bilibili.livemonitor.util.BiliTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationBuilderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val builder = NotificationBuilder(
        context,
        BiliTargets.ROOM_ID,
        { Intent(Intent.ACTION_VIEW) },
        { Intent(Intent.ACTION_VIEW) }
    )

    @Test
    fun `quiet activity notification is explicitly silent`() {
        builder.sendVideo(123L, "title", "新视频投稿", silent = true)

        val notification = notificationManager().getNotification(LiveMonitorApp.NOTIFICATION_ID_VIDEO)!!
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertEquals("silent", notification.group)
    }

    @Test
    fun `live alert exposes watch activity and stop sound service actions`() {
        builder.sendAlert()

        val notification = notificationManager().getNotification(LiveMonitorApp.NOTIFICATION_ID_ALERT)!!
        val actions = notification.actions.associateBy { it.title.toString() }
        assertEquals(
            com.bilibili.livemonitor.MainActivity.ACTION_OPEN_WATCH_LIVE,
            shadowOf(actions.getValue("观看直播").actionIntent).savedIntent.action
        )
        assertEquals(
            LiveCheckService.ACTION_STOP_ALERT,
            shadowOf(actions.getValue("停止声音").actionIntent).savedIntent.action
        )
    }

    private fun notificationManager() = shadowOf(
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    )
}
