package com.bilibili.livemonitor.service

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.util.AlertSoundProvider
import com.bilibili.livemonitor.util.FakeExoPlayer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
class MagicAlertServiceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<ServiceController<MagicAlertService>>()
    private val players = mutableListOf<FakeExoPlayer>()

    private class ThrowingSoundProvider : AlertSoundProvider() {
        override fun setupDataSource(context: Context, player: Player, uriPref: String?): Boolean {
            throw IllegalStateException("simulated setup failure")
        }
    }

    @Before
    fun setUp() {
        MagicAlertService.playerFactory = FakeExoPlayer.newFactory(players)
        MagicAlertService.alertSoundProvider = ThrowingSoundProvider()
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        MagicAlertService.playerFactory = { context -> ExoPlayer.Builder(context).build() }
        MagicAlertService.alertSoundProvider = AlertSoundProvider()
    }

    @Test
    fun `铃声初始化异常时 已创建播放器必须释放`() {
        val controller = Robolectric.buildService(MagicAlertService::class.java)
        controllers += controller
        controller.create()

        controller.withIntent(Intent(context, MagicAlertService::class.java).apply {
            action = MagicAlertService.ACTION_PLAY
        }).startCommand(0, 1)

        assertTrue("初始化异常时不得泄漏播放器", players.single().released)
    }
}
