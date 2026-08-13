package com.bilibili.livemonitor.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        StreamSessionEntity::class,
        StreamTitleChangeEntity::class,
        MoodEventEntity::class,
        PopularityPointEntity::class,
        FollowerSnapshotEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun streamSessionDao(): StreamSessionDao

    abstract fun moodEventDao(): MoodEventDao


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1→v2：新增心情事件表（场次/标题变化数据不动） */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mood_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `event_ts` INTEGER NOT NULL,
                        `mood` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `reason` TEXT,
                        `note` TEXT,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2→v3：心情事件加时长（分钟，默认 0 = 不记时长） */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `mood_events` ADD COLUMN `duration_min` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v3→v4：直播人气采样点表 */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `popularity_points` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_id` INTEGER NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `online` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v4→v5：场次加当场直播封面本地路径（可空，封面收藏） */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stream_sessions` ADD COLUMN `cover_path` TEXT")
            }
        }

        /** v5→v6：粉丝数每日快照表 */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `follower_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `follower_num` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vivhite.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
                ).build().also { INSTANCE = it }
            }
        }
    }
}
