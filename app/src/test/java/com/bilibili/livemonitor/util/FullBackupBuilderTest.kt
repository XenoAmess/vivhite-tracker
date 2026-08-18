package com.bilibili.livemonitor.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.FullBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FullBackupBuilderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        val db = AppDatabase.get(context)
        db.streamSessionDao().deleteAllTitleChanges()
        db.streamSessionDao().deleteAllPopularityPoints()
        db.streamSessionDao().deleteAllFollowerSnapshots()
        db.streamSessionDao().deleteAll()
        db.moodEventDao().deleteAll()
    }

    @Test
    fun `导出全部场次不截断最近500且保留开放场次`() = runBlocking {
        val dao = AppDatabase.get(context).streamSessionDao()
        repeat(501) { index ->
            dao.insertSession(
                StreamSessionEntity(
                    startTs = 1_700_000_000_000L + index * 10_000L,
                    endTs = 1_700_000_005_000L + index * 10_000L,
                    title = "场次$index"
                )
            )
        }
        dao.insertSession(StreamSessionEntity(startTs = 1_800_000_000_000L, title = "进行中"))

        val unpacked = FullBackup.unpack(FullBackupBuilder.build(context))
        assertEquals(502, unpacked.sessions.size)
        val open = unpacked.sessions.single { it.title == "进行中" }
        assertNull(open.endTs)
    }
}
