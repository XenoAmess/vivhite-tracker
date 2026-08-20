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
        FollowerSnapshotEntity::class,
        MediaSnapshotEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun streamSessionDao(): StreamSessionDao

    abstract fun moodEventDao(): MoodEventDao

    abstract fun mediaSnapshotDao(): MediaSnapshotDao


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

        /** v6→v7：查询索引 + 场次子表外键级联；历史孤儿行先清理。 */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 历史版本没有约束开放行数量；保留最新一行，其余闭合到最新场开始。
                db.execSQL(
                    "UPDATE `stream_sessions` SET `end_ts` = MAX(`start_ts`, " +
                        "(SELECT MAX(`start_ts`) FROM `stream_sessions` WHERE `end_ts` IS NULL)) " +
                        "WHERE `end_ts` IS NULL AND `id` != (SELECT `id` FROM `stream_sessions` " +
                        "WHERE `end_ts` IS NULL ORDER BY `start_ts` DESC, `id` DESC LIMIT 1)"
                )
                db.execSQL(
                    "DELETE FROM `stream_title_changes` WHERE `session_id` NOT IN " +
                        "(SELECT `id` FROM `stream_sessions`)"
                )
                db.execSQL(
                    "DELETE FROM `popularity_points` WHERE `session_id` NOT IN " +
                        "(SELECT `id` FROM `stream_sessions`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stream_title_changes_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_id` INTEGER NOT NULL,
                        `changed_at` INTEGER NOT NULL,
                        `old_title` TEXT,
                        `new_title` TEXT,
                        FOREIGN KEY(`session_id`) REFERENCES `stream_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `stream_title_changes_new` (`id`, `session_id`, `changed_at`, `old_title`, `new_title`) " +
                        "SELECT `id`, `session_id`, `changed_at`, `old_title`, `new_title` FROM `stream_title_changes`"
                )
                db.execSQL("DROP TABLE `stream_title_changes`")
                db.execSQL("ALTER TABLE `stream_title_changes_new` RENAME TO `stream_title_changes`")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `popularity_points_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_id` INTEGER NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `online` INTEGER NOT NULL,
                        FOREIGN KEY(`session_id`) REFERENCES `stream_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `popularity_points_new` (`id`, `session_id`, `ts`, `online`) " +
                        "SELECT `id`, `session_id`, `ts`, `online` FROM `popularity_points`"
                )
                db.execSQL("DROP TABLE `popularity_points`")
                db.execSQL("ALTER TABLE `popularity_points_new` RENAME TO `popularity_points`")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_sessions_start_ts` ON `stream_sessions` (`start_ts`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mood_events_event_ts` ON `mood_events` (`event_ts`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_follower_snapshots_ts` ON `follower_snapshots` (`ts`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_title_changes_session_id_changed_at` ON `stream_title_changes` (`session_id`, `changed_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_popularity_points_session_id_ts` ON `popularity_points` (`session_id`, `ts`)")
            }
        }

        /** v7→v8：头像与直播封面发现事件，原图仍保存在 filesDir 下。 */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `observed_at` INTEGER NOT NULL,
                        `content_key` TEXT NOT NULL,
                        `source_url` TEXT,
                        `file_name` TEXT NOT NULL,
                        `session_start_ts` INTEGER,
                        `title` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_snapshots_kind_observed_at` " +
                        "ON `media_snapshots` (`kind`, `observed_at`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_snapshots_kind_content_key` " +
                        "ON `media_snapshots` (`kind`, `content_key`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_snapshots_session_start_ts` " +
                        "ON `media_snapshots` (`session_start_ts`)"
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
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                ).build().also { INSTANCE = it }
            }
        }
    }
}
