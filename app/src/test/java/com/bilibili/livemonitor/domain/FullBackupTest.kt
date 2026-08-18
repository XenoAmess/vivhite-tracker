package com.bilibili.livemonitor.domain

import com.bilibili.livemonitor.db.FollowerSnapshotEntity
import com.bilibili.livemonitor.db.MoodEventEntity
import com.bilibili.livemonitor.db.StreamSessionEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBackupTest {

    private fun sampleData() = FullBackup.Data(
        sessions = listOf(
            StreamSessionEntity(
                startTs = 1_700_000_000_000, endTs = 1_700_003_600_000,
                title = "失眠，杂谈", coverPath = "ab12cd.jpg"
            ),
            StreamSessionEntity(startTs = 1_700_010_000_000, endTs = null, title = null)
        ),
        moods = listOf(
            MoodEventEntity(
                eventTs = 1_700_001_800_000, durationMin = 90, mood = "happy",
                title = "看了场直播", reason = "她唱了我点的歌", note = "下次还点",
                createdAt = 1_699_999_999_123
            )
        ),
        titleChanges = listOf(
            FullBackup.TitleChangeRow(
                sessionStart = 1_700_000_000_000, sessionEnd = 1_700_003_600_000,
                changedAt = 1_700_000_600_000, oldTitle = "开场", newTitle = "换了个主题"
            )
        ),
        popularity = listOf(
            FullBackup.PopularityRow(
                sessionStart = 1_700_000_000_000, sessionEnd = 1_700_003_600_000,
                ts = 1_700_000_060_000, online = 1234
            )
        ),
        followers = listOf(FollowerSnapshotEntity(ts = 1_700_000_000_000, followerNum = 22420)),
        prefsJson = """{"magic_periods":"[{\"start\":1,\"end\":2}]","quiet_enabled":true}""",
        covers = mapOf("ab12cd.jpg" to ByteArray(2048) { (it % 251).toByte() })
    )

    @Test
    fun `zip 全段往返保真`() {
        val bytes = FullBackup.pack(sampleData())
        // PK 头
        assertTrue(bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())

        val d = FullBackup.unpack(bytes)
        assertEquals(2, d.sessions.size)
        assertEquals(1_700_000_000_000, d.sessions[0].startTs)
        assertEquals("失眠，杂谈", d.sessions[0].title)
        assertEquals("ab12cd.jpg", d.sessions[0].coverPath)
        assertNull(d.sessions[1].endTs)

        assertEquals(1, d.moods.size)
        assertEquals("happy", d.moods[0].mood)
        assertEquals(90, d.moods[0].durationMin)
        assertEquals("她唱了我点的歌", d.moods[0].reason)
        assertEquals(1_699_999_999_123, d.moods[0].createdAt)

        assertEquals(1, d.titleChanges.size)
        assertEquals("换了个主题", d.titleChanges[0].newTitle)
        assertEquals(1_700_003_600_000, d.titleChanges[0].sessionEnd)

        assertEquals(1, d.popularity.size)
        assertEquals(1234, d.popularity[0].online)

        assertEquals(1, d.followers.size)
        assertEquals(22420L, d.followers[0].followerNum)

        assertTrue(d.prefsJson!!.contains("magic_periods"))
        assertArrayEquals(
            ByteArray(2048) { (it % 251).toByte() },
            d.covers["ab12cd.jpg"]
        )
    }

    @Test
    fun `进行中行与空快照空封面可往返`() {
        val d = sampleData().copy(prefsJson = null, covers = emptyMap())
        val unpacked = FullBackup.unpack(FullBackup.pack(d))
        assertNull(unpacked.prefsJson)
        assertTrue(unpacked.covers.isEmpty())
        assertEquals(2, unpacked.sessions.size)
        assertNull(unpacked.sessions[1].endTs)
    }

    @Test
    fun `损坏 ZIP 返回受控错误`() {
        val error = runCatching {
            FullBackup.unpack(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 1, 2, 3))
        }.exceptionOrNull()
        assertTrue(error is FullBackup.DamagedBackupException)
    }

    @Test
    fun `未来版本返回不兼容错误`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(FullBackup.ENTRY_MANIFEST))
            zip.write("""{"format":"vivhite-full-backup","version":999}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(FullBackup.ENTRY_SESSIONS))
            zip.write((SessionBackup.HEADER + "\n").toByteArray())
            zip.closeEntry()
        }
        val error = runCatching { FullBackup.unpack(out.toByteArray()) }.exceptionOrNull()
        assertTrue(error is FullBackup.IncompatibleBackupException)
        assertEquals(999, (error as FullBackup.IncompatibleBackupException).version)
    }

    @Test
    fun `流式解包把封面落盘而非留在内存`() {
        val dir = kotlin.io.path.createTempDirectory("full-backup-test").toFile()
        try {
            val data = FullBackup.unpack(
                java.io.ByteArrayInputStream(FullBackup.pack(sampleData())), dir
            )
            assertTrue(data.covers.isEmpty())
            assertArrayEquals(sampleData().covers.getValue("ab12cd.jpg"), data.coverFiles.getValue("ab12cd.jpg").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `未闭合CSV引号被识别为损坏备份`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(FullBackup.ENTRY_SESSIONS))
            zip.write((SessionBackup.HEADER + "\n场次,\"未闭合").toByteArray())
            zip.closeEntry()
        }

        assertTrue(
            runCatching { FullBackup.unpack(out.toByteArray()) }.exceptionOrNull()
                is FullBackup.DamagedBackupException
        )
    }

    @Test
    fun `未知ZIP条目也受展开大小限制`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(FullBackup.ENTRY_SESSIONS))
            zip.write((SessionBackup.HEADER + "\n").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("unknown.bin"))
            val block = ByteArray(1024)
            repeat(16 * 1024 + 1) { zip.write(block) }
            zip.closeEntry()
        }

        assertTrue(
            runCatching { FullBackup.unpack(out.toByteArray()) }.exceptionOrNull()
                is FullBackup.DamagedBackupException
        )
    }
}
