package com.bilibili.livemonitor.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.db.MediaSnapshotEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import com.bilibili.livemonitor.domain.FullBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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
        db.mediaSnapshotDao().deleteAll()
        java.io.File(context.filesDir, "covers").deleteRecursively()
        java.io.File(context.filesDir, "avatars").deleteRecursively()
        java.io.File(context.filesDir, "posters").deleteRecursively()
        java.io.File(context.filesDir, "logs").deleteRecursively()
        java.io.File(context.filesDir, "anchor_avatar.jpg").delete()
        PreferenceManager(context).setLegacyMediaImported(false)
        Unit
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

    @Test
    fun `导出包含月报海报和运行日志`() = runBlocking {
        val posterDir = File(context.filesDir, "posters").apply { mkdirs() }
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        File(posterDir, "monthly_2026-07.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val logDir = File(context.filesDir, "logs").apply { mkdirs() }
        File(logDir, "monitor.log").writeText("2026-08-21 检测正常\n")

        val unpacked = FullBackup.unpack(FullBackupBuilder.build(context))

        assertTrue(unpacked.posters.containsKey("monthly_2026-07.png"))
        assertEquals("2026-08-21 检测正常\n", unpacked.logBytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `导出媒体快照和孤立头像原文件`() = runBlocking {
        val uniqueTs = 1_900_000_123_456L
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val contentKey = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        AppDatabase.get(context).mediaSnapshotDao().insertSnapshot(
            MediaSnapshotEntity(
                id = 0,
                kind = "avatar",
                observedAt = uniqueTs,
                contentKey = contentKey,
                sourceUrl = null,
                fileName = "orphan-avatar.png",
                sessionStartTs = null,
                title = null
            )
        )
        val avatar = java.io.File(context.filesDir, "avatars/orphan-avatar.png")
        avatar.parentFile!!.mkdirs()
        avatar.writeBytes(bytes)
        val cover = java.io.File(context.filesDir, "covers/orphan-cover.png")
        cover.parentFile!!.mkdirs()
        cover.writeBytes(bytes)

        val unpacked = FullBackup.unpack(FullBackupBuilder.build(context))

        assertEquals(1, unpacked.mediaSnapshots.count { it.observedAt == uniqueTs })
        assertArrayEquals(bytes, unpacked.avatars.getValue("orphan-avatar.png"))
        assertArrayEquals(bytes, unpacked.covers.getValue("orphan-cover.png"))
    }

    @Test
    fun `升级后首次备份会先收录旧头像和场次封面`() = runBlocking {
        val bytes = validPngBytes()
        File(context.filesDir, "anchor_avatar.jpg").writeBytes(bytes)
        val cover = File(context.filesDir, "covers/legacy-cover.png")
        cover.parentFile!!.mkdirs()
        cover.writeBytes(bytes)
        AppDatabase.get(context).streamSessionDao().insertSession(
            StreamSessionEntity(startTs = 1_700_000_000_000L, endTs = 1_700_000_100_000L, coverPath = cover.absolutePath)
        )

        val unpacked = FullBackup.unpack(FullBackupBuilder.build(context))

        assertEquals(1, unpacked.avatarNames.size)
        assertEquals(1, unpacked.coverNames.size)
        assertEquals(1, unpacked.mediaSnapshots.count { it.kind == MediaSnapshotEntity.KIND_AVATAR })
        assertEquals(1, unpacked.mediaSnapshots.count { it.kind == MediaSnapshotEntity.KIND_ROOM_COVER })
    }

    private fun validPngBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
