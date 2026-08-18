package com.bilibili.livemonitor.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移链正式测试：MigrationTestHelper 按 schemas/ 里的编译期生成
 * schema JSON 建老库 → 跑迁移链 → 按目标版本 schema 严格校验表结构。
 * 老版本 schema 不再手写（杜绝与 Room 真实生成漂移的假绿）。
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val dbName = "migration_helper_test.db"

    @Test
    fun migrate1To4() = runBlocking {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO stream_sessions (start_ts, end_ts, title) VALUES (1000, 2000, '旧场次')")
            execSQL("INSERT INTO stream_title_changes (session_id, changed_at, old_title, new_title) VALUES (1, 1500, 'a', 'b')")
            close()
        }
        // 全链迁移 + 按 v4 schema 校验
        helper.runMigrationsAndValidate(
            dbName, 4, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7
        ).close()
        // 校验过后用 Room 正常打开做 DAO 断言
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java, dbName
        ).addMigrations(
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7
        ).build()
        val sessions = db.streamSessionDao().recentSessions(5)
        assertEquals(1, sessions.size)
        assertEquals("旧场次", sessions[0].title)
        assertEquals(2000L, sessions[0].endTs)
        assertEquals(1, db.streamSessionDao().titleChanges(sessions[0].id).size)

        // 新表可读写（mood_events 带 duration_min 默认值 / popularity_points）
        val moodId = db.moodEventDao().insert(
            MoodEventEntity(eventTs = 3000, mood = "happy", title = "迁移后写入", createdAt = 3000)
        )
        assertNotNull(db.moodEventDao().eventsBetween(0, 4000).firstOrNull { it.id == moodId })
        assertEquals(
            0,
            db.moodEventDao().eventsBetween(0, 4000).first { it.id == moodId }.durationMin
        )
        db.streamSessionDao().insertPopularityPoint(
            PopularityPointEntity(sessionId = sessions[0].id, ts = 1500, online = 42)
        )
        assertEquals(42, db.streamSessionDao().popularityPoints(sessions[0].id).first().online)
        Unit
    }

    @Test
    fun migrate2To4() = runBlocking {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO mood_events (event_ts, mood, title, created_at) VALUES (5000, 'sad', 'v2心情', 5000)"
            )
            close()
        }
        helper.runMigrationsAndValidate(
            dbName, 4, true,
            AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4
        ).close()
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java, dbName
        ).addMigrations(
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7
        ).build()
        // v2 心情数据迁移后 duration_min 补默认 0
        val moods = db.moodEventDao().eventsBetween(0, 10_000)
        assertEquals(1, moods.size)
        assertEquals("v2心情", moods[0].title)
        assertEquals(0, moods[0].durationMin)
        Unit
    }
    @Test
    fun migrate3To4() = runBlocking {
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO mood_events (event_ts, duration_min, mood, title, created_at) VALUES (6000, 90, 'sad', 'v3心情', 6000)"
            )
            close()
        }
        helper.runMigrationsAndValidate(
            dbName, 4, true, AppDatabase.MIGRATION_3_4
        ).close()
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java, dbName
        ).addMigrations(
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7
        ).build()

        val moods = db.moodEventDao().eventsBetween(0, 10_000)
        assertEquals(1, moods.size)
        assertEquals(90, moods[0].durationMin)
        val sessionId = db.streamSessionDao().insertSession(StreamSessionEntity(startTs = 50))
        db.streamSessionDao().insertPopularityPoint(
            PopularityPointEntity(sessionId = sessionId, ts = 100, online = 7)
        )
        assertEquals(1, db.streamSessionDao().popularityPoints(sessionId).size)
        db.close()
        Unit
    }

    @Test
    fun migrate4To5() = runBlocking {
        helper.createDatabase(dbName, 4).apply {
            execSQL("INSERT INTO stream_sessions (start_ts, end_ts, title) VALUES (1000, 2000, 'v4场次')")
            execSQL("INSERT INTO popularity_points (session_id, ts, online) VALUES (1, 1500, 42)")
            close()
        }
        helper.runMigrationsAndValidate(
            dbName, 5, true, AppDatabase.MIGRATION_4_5
        ).close()
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java, dbName
        ).addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7).build()

        val sessions = db.streamSessionDao().recentSessions(5)
        assertEquals(1, sessions.size)
        assertEquals("v4场次", sessions[0].title)
        assertNull("v4 老行 cover_path 应为 null", sessions[0].coverPath)
        // v5 新列可写
        db.streamSessionDao().updateSession(sessions[0].copy(coverPath = "/tmp/cover.jpg"))
        assertEquals(
            "/tmp/cover.jpg",
            db.streamSessionDao().recentSessions(5).first().coverPath
        )
        db.close()
        Unit
    }

    @Test
    fun migrate5To6() = runBlocking {
        helper.createDatabase(dbName, 5).apply {
            execSQL("INSERT INTO stream_sessions (start_ts, end_ts, title, cover_path) VALUES (1000, 2000, 'v5场次', '/tmp/c.jpg')")
            close()
        }
        helper.runMigrationsAndValidate(
            dbName, 6, true, AppDatabase.MIGRATION_5_6
        ).close()
        val db = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java, dbName
        ).addMigrations(AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7).build()

        val sessions = db.streamSessionDao().recentSessions(5)
        assertEquals(1, sessions.size)
        assertEquals("/tmp/c.jpg", sessions[0].coverPath)
        // v6 新表可写
        db.streamSessionDao().insertFollowerSnapshot(
            FollowerSnapshotEntity(ts = 100, followerNum = 22420)
        )
        assertEquals(1, db.streamSessionDao().followerSnapshots().size)
        db.close()
        Unit
    }

    @Test
    fun migrate6To7AddsIndexesRemovesOrphansAndCascades() {
        helper.createDatabase(dbName, 6).apply {
            execSQL("PRAGMA foreign_keys = OFF")
            execSQL("INSERT INTO stream_sessions (id, start_ts, end_ts, title, cover_path) VALUES (1, 1000, 2000, 'v6场次', NULL)")
            execSQL("INSERT INTO stream_title_changes (session_id, changed_at, new_title) VALUES (1, 1500, '有效')")
            execSQL("INSERT INTO stream_title_changes (session_id, changed_at, new_title) VALUES (99, 1600, '孤儿')")
            execSQL("INSERT INTO popularity_points (session_id, ts, online) VALUES (1, 1500, 42)")
            execSQL("INSERT INTO popularity_points (session_id, ts, online) VALUES (99, 1600, 7)")
            execSQL("INSERT INTO stream_sessions (id, start_ts, end_ts, title, cover_path) VALUES (2, 3000, NULL, '旧开放行', NULL)")
            execSQL("INSERT INTO stream_sessions (id, start_ts, end_ts, title, cover_path) VALUES (3, 4000, NULL, '最新开放行', NULL)")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 7, true, AppDatabase.MIGRATION_6_7
        )
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM stream_title_changes"))
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM popularity_points"))
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM stream_sessions WHERE end_ts IS NULL"))
        assertEquals(4000, scalar(db, "SELECT end_ts FROM stream_sessions WHERE id = 2"))
        assertEquals(
            5,
            listOf(
                "index_stream_sessions_start_ts",
                "index_mood_events_event_ts",
                "index_follower_snapshots_ts",
                "index_stream_title_changes_session_id_changed_at",
                "index_popularity_points_session_id_ts"
            ).count { indexName ->
                db.query("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(indexName))
                    .use { it.moveToFirst() }
            }
        )

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM stream_sessions WHERE id = 1")
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM stream_title_changes"))
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM popularity_points"))
        db.close()
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
