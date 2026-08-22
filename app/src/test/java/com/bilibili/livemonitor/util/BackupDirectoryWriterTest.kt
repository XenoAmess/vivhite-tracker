package com.bilibili.livemonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupDirectoryWriterTest {

    private fun ts(dayOffset: Long, sameDayIndex: Int = 0): Long =
        NOW + dayOffset * DAY_MS + sameDayIndex * 60_000L

    private fun nameAt(time: Long): String = BackupDirectoryWriter.backupFileName(time)

    @Test
    fun `文件名带日期时间且可解析回同一时间`() {
        val name = nameAt(NOW)
        assertTrue(name.matches(Regex("^vivhite_backup_\\d{8}_\\d{6}\\.zip$")))
        assertEquals(
            NOW / 60_000L,
            BackupDirectoryWriter.backupTimeMillis(name)!! / 60_000L
        )
    }

    @Test
    fun `旧版仅日期文件名按当天零点解析`() {
        val millis = BackupDirectoryWriter.backupTimeMillis("vivhite_backup_20260801.zip")
        assertEquals(
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).parse("20260801")!!.time,
            millis
        )
    }

    @Test
    fun `非备份文件名不解析`() {
        assertNull(BackupDirectoryWriter.backupTimeMillis("notes.txt"))
        assertNull(BackupDirectoryWriter.backupTimeMillis("vivhite_backup_abc.zip"))
        assertNull(BackupDirectoryWriter.backupTimeMillis("vivhite_backup_20260801_extra.zip"))
        assertNull(BackupDirectoryWriter.backupTimeMillis("../vivhite_backup_20260801.zip"))
    }

    @Test
    fun `超过30天的备份被清理`() {
        val names = listOf(
            nameAt(ts(-31)),
            nameAt(ts(-30)),
            nameAt(ts(-1)),
            nameAt(ts(0))
        )
        val deleted = BackupDirectoryWriter.namesToDelete(names, NOW)
        assertEquals(listOf(nameAt(ts(-31))), deleted)
    }

    @Test
    fun `同日多份都保留且不误删其他文件`() {
        val names = listOf(
            nameAt(ts(0, 0)),
            nameAt(ts(0, 1)),
            nameAt(ts(0, 2)),
            "用户自己的文件.zip",
            "vivhite_backup_notes.txt"
        )
        assertTrue(BackupDirectoryWriter.namesToDelete(names, NOW).isEmpty())
    }

    @Test
    fun `超过60份时删除最旧的`() {
        val names = (0 until 65).map { nameAt(NOW - it * 60_000L) } // 65 份，1 分钟一份
        val deleted = BackupDirectoryWriter.namesToDelete(names, NOW)
        assertEquals(5, deleted.size)
        val remaining = names - deleted.toSet()
        assertEquals(60, remaining.size)
        // 留下的都是最新的 60 份
        assertTrue(nameAt(NOW) in remaining)
        assertTrue(nameAt(NOW - 64 * 60_000L) !in remaining)
    }

    @Test
    fun `30天清理与60份上限同时生效`() {
        val names = (0 until 70).map { nameAt(NOW - it * 60_000L) } + nameAt(ts(-40))
        val deleted = BackupDirectoryWriter.namesToDelete(names, NOW)
        assertTrue(nameAt(ts(-40)) in deleted)
        assertEquals(11, deleted.size) // 40 天前的 1 份 + 超额的 10 份
    }

    @Test
    fun `旧版日期名参与份数上限清理`() {
        val old = (1..5).map { "vivhite_backup_202608%02d.zip".format(it) }
        val recent = (0 until 58).map { nameAt(NOW - it * 60_000L) }
        val deleted = BackupDirectoryWriter.namesToDelete(old + recent, NOW)
        assertEquals(3, deleted.size) // 63 份 → 删最旧 3 份（旧版日期名最旧）
        assertTrue(deleted.all { it.startsWith("vivhite_backup_202608") })
    }

    private companion object {
        private const val DAY_MS = 86_400_000L
        private val NOW: Long = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .parse("20260821_120000")!!.time
    }
}
