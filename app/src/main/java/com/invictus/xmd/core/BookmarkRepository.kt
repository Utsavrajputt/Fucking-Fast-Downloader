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
