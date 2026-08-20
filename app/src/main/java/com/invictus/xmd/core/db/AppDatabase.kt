package com.invictus.xmd.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.invictus.xmd.core.Bookmark
import com.invictus.xmd.core.HistoryEntry
import com.invictus.xmd.core.QueueItem

@Database(entities = [QueueItem::class, Bookmark::class, HistoryEntry::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun queueItemDao(): QueueItemDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // v1 -> v2: adds the bookmarks table (Browser tab speed-dial).
        // Explicit migration instead of fallbackToDestructiveMigration so
        // the existing queue_items table (and any in-flight downloads) on
        // upgrading installs isn't wiped.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `faviconUrl` TEXT,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `createdAtMs` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        // v2 -> v3: adds the history_entries table (Browser tab visited pages).
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `history_entries` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `visitedAtMs` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ff_queue.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // Safety net only for schema drift beyond the explicit
                    // migrations above (shouldn't trigger in practice).
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
