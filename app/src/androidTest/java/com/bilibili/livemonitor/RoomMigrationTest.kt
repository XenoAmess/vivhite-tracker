package com.bilibili.livemonitor.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移链真机/模拟器测试：手工建 v1 schema（两张原始表）→  Room 打开
 * 触发 1→2→3→4 全链迁移 → 老数据完好 + 新表可用。
 * 防的是：老用户从 v1 一路升级到最新版时 schema 不匹配直接崩。
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun createV1Database() {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                // v1 原始 schema（stream_sessions + stream_title_changes）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stream_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `start_ts` INTEGER NOT NULL,
                        `end_ts` INTEGER,
                        `title` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stream_title_changes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_id` INTEGER NOT NULL,
                        `changed_at` INTEGER NOT NULL,
                        `old_title` TEXT,
                        `new_title` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO stream_sessions (start_ts, end_ts, title) VALUES (1000, 2000, '旧场次')"
                )
                db.execSQL(
                    "INSERT INTO stream_title_changes (session_id, changed_at, old_title, new_title) VALUES (1, 1500, 'a', 'b')"
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        helper.writableDatabase.close()
    }

    @Test
    fun migrateV1ToLatest() = runBlocking {
        context.deleteDatabase(dbName)
        createV1Database()

        // Room 打开 → 应跑 1→2→3→4 迁移链
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4
            ).build()

        // v1 老数据完好
        val sessions = db.streamSessionDao().recentSessions(5)
        assertEquals(1, sessions.size)
        assertEquals("旧场次", sessions[0].title)
        assertEquals(1000L, sessions[0].startTs)
        assertEquals(2000L, sessions[0].endTs)
        assertEquals(1, db.streamSessionDao().titleChanges(sessions[0].id).size)

        // v2/v3/v4 新表可用：心情（含 duration_min 默认值）与人气点读写
        val moodId = db.moodEventDao().insert(
            MoodEventEntity(eventTs = 3000, mood = "happy", title = "迁移后写入", createdAt = 3000)
        )
        assertNotNull(db.moodEventDao().eventsBetween(0, 4000).firstOrNull { it.id == moodId })
        assertEquals(0, db.moodEventDao().eventsBetween(0, 4000).first { it.id == moodId }.durationMin)

        db.streamSessionDao().insertPopularityPoint(
            PopularityPointEntity(sessionId = sessions[0].id, ts = 1500, online = 42)
        )
        assertEquals(42, db.streamSessionDao().popularityPoints(sessions[0].id).first().online)

        db.close()
        Unit
    }

    @Test
    fun migrateV3ToV4() = runBlocking {
        context.deleteDatabase(dbName)
        // 手工建 v3 schema（含 mood_events.duration_min）
        val helper = object : SQLiteOpenHelper(context, dbName, null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stream_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `start_ts` INTEGER NOT NULL,
                        `end_ts` INTEGER,
                        `title` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stream_title_changes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_id` INTEGER NOT NULL,
                        `changed_at` INTEGER NOT NULL,
                        `old_title` TEXT,
                        `new_title` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mood_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `event_ts` INTEGER NOT NULL,
                        `duration_min` INTEGER NOT NULL DEFAULT 0,
                        `mood` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `reason` TEXT,
                        `note` TEXT,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO mood_events (event_ts, duration_min, mood, title, created_at) VALUES (5000, 90, 'sad', 'v3心情', 5000)"
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        helper.writableDatabase.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4
            ).build()

        // v3 心情数据完好（含 duration_min）
        val moods = db.moodEventDao().eventsBetween(0, 10_000)
        assertEquals(1, moods.size)
        assertEquals(90, moods[0].durationMin)
        assertEquals("v3心情", moods[0].title)

        // v4 新表可写
        db.streamSessionDao().insertPopularityPoint(
            PopularityPointEntity(sessionId = 1, ts = 100, online = 7)
        )
        assertEquals(1, db.streamSessionDao().popularityPoints(1).size)

        db.close()
        Unit
    }
}
