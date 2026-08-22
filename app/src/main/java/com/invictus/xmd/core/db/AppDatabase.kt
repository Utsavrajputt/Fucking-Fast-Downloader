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

@Database(entities = [QueueItem::class, Bookmark::class, HistoryEntry::class], version = 6, exportSchema = false)
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

        // v3 -> v4: adds YouTube (yt-dlp) fields to queue_items -- platform,
        // the chosen quality's format selector/label, and percent-based
        // progress (yt-dlp reports 0-100%, not bytes, unlike DIRECT/torrent).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_items ADD COLUMN platform TEXT NOT NULL DEFAULT 'DIRECT'")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN mediaFormatSelector TEXT")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN mediaFormatLabel TEXT")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN progressPercent INTEGER NOT NULL DEFAULT -1")
            }
        }

        // v4 -> v5: adds mediaStatusText to queue_items -- speed/ETA/size for
        // the current yt-dlp stage, parsed from its raw stdout line (see
        // Models.kt for why this can't come from the library's own callback
        // alone during postprocessing stages).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_items ADD COLUMN mediaStatusText TEXT")
            }
        }

        // v5 -> v6: adds filePath to queue_items -- the real absolute path a
        // completed download was written to, captured directly at DONE time
        // instead of reconstructed later (see Models.kt for why). Powers the
        // "Open" action in DownloadsFragment.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_items ADD COLUMN filePath TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ff_queue.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // Safety net only for schema drift beyond the explicit
                    // migrations above (shouldn't trigger in practice).
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
