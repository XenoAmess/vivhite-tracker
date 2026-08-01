package com.bilibili.livemonitor.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.domain.MagicPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MagicPeriodStoreTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
        prefs.setMagicPeriodsJson("[]")
    }

    @Test
    fun `round trip 保存读回一致`() {
        val periods = listOf(
            MagicPeriod(1_700_000_000_000L, 1_700_000_100_000L),
            MagicPeriod(1_800_000_000_000L, 1_800_000_200_000L)
        )
        MagicPeriodStore.save(prefs, periods)
        assertEquals(periods, MagicPeriodStore.load(prefs))
    }

    @Test
    fun `损坏JSON回退空表`() {
        prefs.setMagicPeriodsJson("not json at all")
        assertTrue(MagicPeriodStore.load(prefs).isEmpty())

        prefs.setMagicPeriodsJson("""[{"start":-5,"end":100}]""")
        assertTrue(MagicPeriodStore.load(prefs).isEmpty())
    }

    @Test
    fun `默认空表`() {
        assertTrue(MagicPeriodStore.load(prefs).isEmpty())
    }
}
