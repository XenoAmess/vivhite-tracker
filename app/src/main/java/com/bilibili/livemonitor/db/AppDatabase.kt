package com.bilibili.livemonitor.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [StreamSessionEntity::class, StreamTitleChangeEntity::class, MoodEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun streamSessionDao(): StreamSessionDao

    abstract fun moodEventDao(): MoodEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1→v2：新增心情事件表（场次/标题变化数据不动） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
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

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vivhite.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
