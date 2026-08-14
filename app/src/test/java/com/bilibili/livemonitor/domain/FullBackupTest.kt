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
                createdAt = 1_700_001_800_000
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
        // 进行中场次在 CSV 层被跳过（不参与往返）
        assertEquals(1, d.sessions.size)
        assertEquals(1_700_000_000_000, d.sessions[0].startTs)
        assertEquals("失眠，杂谈", d.sessions[0].title)
        assertEquals("ab12cd.jpg", d.sessions[0].coverPath)

        assertEquals(1, d.moods.size)
        assertEquals("happy", d.moods[0].mood)
        assertEquals(90, d.moods[0].durationMin)
        assertEquals("她唱了我点的歌", d.moods[0].reason)

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
    fun `进行中行导入时被跳过（CSV 层语义） 空快照与空封面可省略`() {
        val d = sampleData().copy(prefsJson = null, covers = emptyMap())
        val unpacked = FullBackup.unpack(FullBackup.pack(d))
        assertNull(unpacked.prefsJson)
        assertTrue(unpacked.covers.isEmpty())
        // 进行中场次在 backup.csv 中被跳过
        assertEquals(1, unpacked.sessions.size)
    }
}
