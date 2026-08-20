package com.bilibili.livemonitor.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MediaSnapshotEntity
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.FullBackup
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.worker.BackupWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupRestoreCoordinatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = AppDatabase.get(context)
    private val coordinator = BackupRestoreCoordinator(context, database)

    @Before
    fun setUp() {
        runBlocking {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
            database.streamSessionDao().deleteAllTitleChanges()
            database.streamSessionDao().deleteAllPopularityPoints()
            database.streamSessionDao().deleteAllFollowerSnapshots()
            database.streamSessionDao().deleteAll()
            database.moodEventDao().deleteAll()
            database.mediaSnapshotDao().deleteAll()
            File(context.filesDir, "covers").deleteRecursively()
            File(context.filesDir, "avatars").deleteRecursively()
            PreferenceManager(context).apply {
                setQuietHoursEnabled(false)
                setAutoBackupEnabled(false)
                setBackupTreeUri("")
            }
        }
    }

    @Test
    fun `restores data cover preferences and backup schedule state`() = runBlocking {
        BackupWorker.schedule(context)
        val coverName = "restore.png"
        val report = coordinator.restore(
            FullBackup.Data(
                sessions = listOf(
                    StreamSessionEntity(startTs = 100, endTs = 200, title = "restored", coverPath = coverName)
                ),
                moods = listOf(
                    MoodEventEntity(eventTs = 150, mood = "happy", title = "mood", createdAt = 150)
                ),
                titleChanges = emptyList(),
                popularity = emptyList(),
                followers = emptyList(),
                prefsJson = """{"quiet_enabled":true,"magic_periods":[]}""",
                covers = mapOf(coverName to imageBytes())
            )
        )

        assertEquals(1, report.sessions.added)
        assertEquals(1, report.moods.added)
        assertEquals(1, report.covers.added)
        assertTrue(report.preferencesRestored)
        assertTrue(report.magicPeriodsRestored)
        assertTrue(PreferenceManager(context).isQuietHoursEnabled())
        assertTrue(database.streamSessionDao().allSessions().single().coverPath!!.endsWith(coverName))
        assertTrue(File(context.filesDir, "covers/$coverName").isFile)
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("auto_backup_periodic").get()
        assertTrue(work.isNotEmpty() && work.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `validation failure rolls back the complete database transaction`() = runBlocking {
        val error = runCatching {
            coordinator.restore(
                FullBackup.Data(
                    sessions = listOf(
                        StreamSessionEntity(startTs = 10, endTs = 20),
                        StreamSessionEntity(startTs = 30, endTs = 29)
                    ),
                    moods = emptyList(),
                    titleChanges = emptyList(),
                    popularity = emptyList(),
                    followers = emptyList(),
                    prefsJson = null
                )
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(database.streamSessionDao().allSessions().isEmpty())
    }

    @Test
    fun `older imported open session is closed without replacing current open session`() = runBlocking {
        val dao = database.streamSessionDao()
        val currentId = dao.insertSession(StreamSessionEntity(startTs = 200, title = "current"))

        val report = coordinator.restore(
            FullBackup.Data(
                sessions = listOf(StreamSessionEntity(startTs = 100, title = "imported")),
                moods = emptyList(),
                titleChanges = listOf(
                    FullBackup.TitleChangeRow(100, null, 150, "a", "b")
                ),
                popularity = emptyList(),
                followers = emptyList(),
                prefsJson = null
            )
        )

        val sessions = dao.allSessions()
        assertEquals(1, sessions.count { it.endTs == null })
        assertEquals(currentId, dao.findOpenSession()!!.id)
        val imported = sessions.single { it.startTs == 100L }
        assertEquals(200L, imported.endTs)
        assertEquals(1, dao.titleChanges(imported.id).size)
        assertEquals(1, report.titleChanges.added)
    }

    @Test
    fun `restores avatar atomically and skips duplicate media snapshot on merge`() = runBlocking {
        val observedAt = 1_900_000_654_321L
        val avatarBytes = imageBytes()
        val contentKey = java.security.MessageDigest.getInstance("SHA-1").digest(avatarBytes)
            .joinToString("") { "%02x".format(it) }
        val snapshot = MediaSnapshotEntity(
            id = 0,
            kind = "avatar",
            observedAt = observedAt,
            contentKey = contentKey,
            sourceUrl = "https://example.com/avatar.png",
            fileName = "history-avatar.png",
            sessionStartTs = 100,
            title = "历史头像"
        )
        val data = FullBackup.Data(
            sessions = emptyList(),
            moods = emptyList(),
            titleChanges = emptyList(),
            popularity = emptyList(),
            followers = emptyList(),
            prefsJson = null,
            mediaSnapshots = listOf(snapshot),
            avatars = mapOf(snapshot.fileName to avatarBytes)
        )

        val first = coordinator.restore(data)
        val second = coordinator.restore(data)

        assertEquals(1, first.mediaSnapshots.added)
        assertEquals(1, first.avatars.added)
        assertEquals(1, second.mediaSnapshots.skipped)
        assertEquals(1, second.avatars.skipped)
        assertEquals(
            1,
            database.mediaSnapshotDao().countSnapshot(
                snapshot.kind,
                snapshot.observedAt,
                snapshot.contentKey,
                snapshot.sessionStartTs
            )
        )
        assertTrue(File(context.filesDir, "avatars/${snapshot.fileName}").isFile)
    }

    @Test
    fun `rejects media whose content does not match sha1 key`() = runBlocking {
        val bytes = imageBytes()
        val snapshot = MediaSnapshotEntity(
            kind = MediaSnapshotEntity.KIND_AVATAR,
            observedAt = 1234,
            contentKey = "0".repeat(40),
            fileName = "mismatch.png"
        )
        val error = runCatching {
            coordinator.restore(
                FullBackup.Data(
                    sessions = emptyList(), moods = emptyList(), titleChanges = emptyList(),
                    popularity = emptyList(), followers = emptyList(), prefsJson = null,
                    mediaSnapshots = listOf(snapshot), avatars = mapOf(snapshot.fileName to bytes)
                )
            )
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(database.mediaSnapshotDao().allSnapshots().isEmpty())
        assertTrue(!File(context.filesDir, "avatars/${snapshot.fileName}").exists())
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
