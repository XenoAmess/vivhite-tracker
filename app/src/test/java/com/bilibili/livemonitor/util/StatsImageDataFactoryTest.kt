package com.bilibili.livemonitor.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class StatsImageDataFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDatabase() = runBlocking {
        AppDatabase.get(context).streamSessionDao().deleteAll()
        AppDatabase.get(context).moodEventDao().deleteAll()
    }

    @Test
    fun `月报数据不截断超过五百场的月份`() = runBlocking {
        val month = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = month.timeInMillis
        repeat(520) { index ->
            val sessionStart = start + index * 60_000L
            AppDatabase.get(context).streamSessionDao().insertSession(
                StreamSessionEntity(
                    startTs = sessionStart,
                    endTs = sessionStart + 30_000L,
                    title = "场次 $index"
                )
            )
        }

        val data = StatsImageDataFactory.build(context, month)

        assertEquals(520, data.records.count {
            it.kind == StatsImageRenderer.RecordKind.SESSION
        })
    }
}
