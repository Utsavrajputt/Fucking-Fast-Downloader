package com.invictus.xmd.core

import android.content.Context
import androidx.lifecycle.LiveData
import com.invictus.xmd.core.db.AppDatabase
import com.invictus.xmd.core.db.BookmarkDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Speed-dial tiles shown on the Browser tab's new-tab page. Unlike
 * QueueRepository, there's no in-flight/background-writer state to race
 * against here -- reads/writes are just simple CRUD against Room, so the
 * DAO's own LiveData query is exposed directly instead of hand-rolling a
 * synchronized master list.
 */
object BookmarkRepository {

    private lateinit var dao: BookmarkDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var bookmarks: LiveData<List<Bookmark>>
        private set

    fun init(context: Context) {
        if (::dao.isInitialized) return
        dao = AppDatabase.get(context).bookmarkDao()
        bookmarks = dao.observeAll()
        seedDefaultBookmarks()
    }

    // Default speed-dial shortcuts. Seeded on every init(), but by URL --
    // any URL already present (whether still exactly as seeded, renamed, or
    // previously removed by the user and re-added elsewhere) is left alone,
    // so this only ever fills in ones that are missing rather than
    // duplicating or resurrecting anything the user deleted... actually it
    // *will* re-add one the user deleted, since deletion just removes the
    // row and there's no separate "don't reseed this" marker; that's an
    // accepted tradeoff to keep this simple for a fixed, curated list like
    // this one rather than tracking per-shortcut dismissal state.
    private val DEFAULT_BOOKMARKS = listOf(
        "Vegamovies" to "https://new2.vegamovies.futbol/",
        "RogMovies" to "https://new2.rogmovies.click/",
        "HDHub4u" to "https://new1.hdhub4u.af/?utm=mn1",
        "DesireMovies" to "https://1desiremovies.wales/",
        "FitGirl Repacks" to "https://fitgirl-repacks.site/popular-repacks-of-the-year/",
        "TeraBox Downloader" to "https://teradownloader.com/"
    )

    private fun seedDefaultBookmarks() {
        scope.launch {
            val existing = runCatching { dao.getAll() }.getOrDefault(emptyList())
            val existingUrls = existing.map { it.url }.toSet()
            var nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            DEFAULT_BOOKMARKS.forEach { (title, url) ->
                if (url !in existingUrls) {
                    runCatching {
                        dao.upsert(
                            Bookmark(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                url = url,
                                sortOrder = nextOrder
                            )
                        )
                    }
                    nextOrder++
                }
            }
        }
    }

    fun add(title: String, url: String) {
        scope.launch {
            val nextOrder = (runCatching { dao.getAll() }.getOrDefault(emptyList())
                .maxOfOrNull { it.sortOrder } ?: -1) + 1
            runCatching {
                dao.upsert(
                    Bookmark(
                        id = UUID.randomUUID().toString(),
                        title = title.ifBlank { hostOf(url) },
                        url = url,
                        sortOrder = nextOrder
                    )
                )
            }
        }
    }

    fun remove(bookmark: Bookmark) {
        scope.launch { runCatching { dao.delete(bookmark) } }
    }

    fun rename(bookmark: Bookmark, newTitle: String) {
        scope.launch {
            runCatching { dao.upsert(bookmark.copy(title = newTitle.ifBlank { hostOf(bookmark.url) })) }
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: url
}
