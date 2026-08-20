package com.invictus.xmd.core

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.invictus.xmd.core.db.AppDatabase
import com.invictus.xmd.core.db.QueueItemDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single in-memory source of truth for the queue, shared between MainActivity
 * (UI + resolve flow) and DownloadService (background download loop). Both
 * run in the same process, so a plain LiveData-backed singleton is enough --
 * no cross-process IPC needed.
 *
 * IMPORTANT: reads/writes go through [master] under [lock], not through
 * LiveData.value. LiveData.postValue() from a background thread is
 * fire-and-forget -- .value isn't updated until the main thread processes
 * it -- so a naive "read items.value, map, postValue" pattern race-loses
 * updates when called rapidly from a download thread (e.g. a status change
 * to DOWNLOADING gets silently clobbered by the very next progress tick
 * because that tick's map() was computed from a stale .value read before
 * the status change had been applied). Keeping our own synchronized master
 * list sidesteps that entirely.
 *
 * PERSISTENCE: [master] is mirrored to a Room DB (see core/db/AppDatabase.kt)
 * so the queue survives the app process being killed and restarted -- it
 * used to be purely in-memory, so a restart silently wiped the whole list
 * even though the already-downloaded files on disk were untouched. Call
 * [init] once (from FfApp.onCreate) before anything touches the queue.
 * Writes to Room happen off the main thread and don't block the in-memory
 * update; progress-only ticks (bytesDone/speedBps, which fire up to ~5x/sec
 * per active download) are throttled per-item so we're not hammering SQLite
 * on every tick -- status/error/fileName/directUrl/category changes are
 * always persisted immediately since those matter for correctness after a
 * restart.
 */
object QueueRepository {

    private val lock = Any()
    private var master: List<QueueItem> = emptyList()

    val items = MutableLiveData<List<QueueItem>>(emptyList())

    private lateinit var dao: QueueItemDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPersistMs = ConcurrentHashMap<String, Long>()
    private const val PROGRESS_PERSIST_INTERVAL_MS = 1_000L

    /**
     * Loads whatever was persisted from a previous run, then starts
     * mirroring further changes back to disk. Safe to call once at app
     * startup (FfApp.onCreate); harmless if called again.
     *
     * Items that were mid-flight when the process died (RESOLVING /
     * DOWNLOADING / SAVING) can't just resume -- there's no worker thread
     * for them anymore -- so they're rolled back to a restartable state:
     * READY if we already have a directUrl (download can just restart),
     * otherwise PENDING (needs re-resolve). NEEDS_CHALLENGE/PAUSED/READY/
     * DONE/FAILED are left as-is.
     */
    fun init(context: Context) {
        if (::dao.isInitialized) return
        dao = AppDatabase.get(context).queueItemDao()
        scope.launch {
            val persisted = runCatching { dao.getAll() }.getOrDefault(emptyList())
            val recovered = persisted.map { item ->
                when (item.status) {
                    ItemStatus.RESOLVING -> item.copy(status = ItemStatus.PENDING)
                    ItemStatus.DOWNLOADING, ItemStatus.SAVING, ItemStatus.RETRYING ->
                        if (item.directUrl != null) item.copy(status = ItemStatus.READY)
                        else item.copy(status = ItemStatus.PENDING)
                    else -> item
                }
            }
            synchronized(lock) {
                // Don't clobber anything the UI already queued before this
                // background load finished.
                val current = master.associateBy { it.id }
                val recoveredIds = recovered.map { it.id }.toSet()
                master = recovered.map { current[it.id] ?: it } +
                    master.filter { it.id !in recoveredIds }
                items.postValue(master)
            }
            // Persist any status rollback we just did.
            val changed = recovered.filter { r ->
                persisted.find { it.id == r.id }?.status != r.status
            }
            if (changed.isNotEmpty()) runCatching { dao.upsertAll(changed) }
        }
    }

    /**
     * Category is auto-detected per link from its extension (see
     * [CategoryDetector]) -- there's no manual picker anymore. Items
     * already in-flight keep whatever category they were queued under,
     * even if a re-resolve would now detect differently -- their
     * destination folder shouldn't move mid-download.
     *
     * IMPORTANT: this is additive, not a replace. It used to rebuild [master]
     * from just [rawLinks] (the current paste-box contents), which silently
     * dropped every previously-queued item -- including ones actively
     * downloading -- the moment a second batch was pasted, since they weren't
     * present in the new rawLinks. Now we keep every existing item and only
     * add/replace entries for the links just passed in, so an in-flight
     * download from a prior call is never removed from [master].
     */
    fun setLinks(rawLinks: List<String>) {
        val toPersist: List<QueueItem>
        synchronized(lock) {
            val current = master.associateBy { it.sourceUrl }
            val updatedOrNew = rawLinks.map { link ->
                val existing = current[link]
                when {
                    existing == null ->
                        QueueItem(id = UUID.randomUUID().toString(), sourceUrl = link, category = CategoryDetector.detect(link))
                    // Finished or failed/cancelled items get a clean retry instead of being
                    // stuck reusing their old terminal status (which Prepare would then skip).
                    existing.status == ItemStatus.DONE || existing.status == ItemStatus.FAILED ->
                        QueueItem(id = UUID.randomUUID().toString(), sourceUrl = link, category = CategoryDetector.detect(link))
                    else -> existing // leave anything still in-flight alone
                }
            }
            val untouched = master.filter { it.sourceUrl !in rawLinks.toSet() }
            master = untouched + updatedOrNew
            items.postValue(master)
            toPersist = updatedOrNew
        }
        persistNow(toPersist)
    }

    fun update(id: String, mutate: (QueueItem) -> QueueItem) {
        var previous: QueueItem? = null
        var updated: QueueItem? = null
        synchronized(lock) {
            master = master.map {
                if (it.id == id) {
                    previous = it
                    val mutated = mutate(it)
                    updated = mutated
                    mutated
                } else it
            }
            items.postValue(master)
        }
        updated?.let { persistDebounced(it, previous) }
    }

    /**
     * Atomically finds the first READY item and marks it DOWNLOADING in one
     * step, so multiple concurrent download workers can't both grab the
     * same item.
     */
    fun claimNextReady(): QueueItem? {
        var claimedItem: QueueItem? = null
        synchronized(lock) {
            val idx = master.indexOfFirst { it.status == ItemStatus.READY }
            if (idx == -1) return null
            val claimed = master[idx].copy(
                status = ItemStatus.DOWNLOADING,
                downloadStartedAtMs = System.currentTimeMillis()
            )
            master = master.toMutableList().also { it[idx] = claimed }
            items.postValue(master)
            claimedItem = claimed
        }
        claimedItem?.let { persistNow(listOf(it)) }
        return claimedItem
    }

    /** Removes a single item from the queue (used by the per-item "Clear" button). */
    fun removeItem(id: String) {
        synchronized(lock) {
            master = master.filter { it.id != id }
            items.postValue(master)
        }
        if (::dao.isInitialized) {
            scope.launch { runCatching { dao.deleteByIds(listOf(id)) } }
        }
    }

    fun clearFinishedAndFailed() {
        val removedIds: List<String>
        synchronized(lock) {
            val (removed, kept) = master.partition { it.status == ItemStatus.DONE || it.status == ItemStatus.FAILED }
            master = kept
            items.postValue(master)
            removedIds = removed.map { it.id }
        }
        if (removedIds.isNotEmpty() && ::dao.isInitialized) {
            scope.launch { runCatching { dao.deleteByIds(removedIds) } }
        }
    }

    fun current(): List<QueueItem> = synchronized(lock) { master }

    // ── Persistence helpers ─────────────────────────────────────────────

    private fun persistNow(items: List<QueueItem>) {
        if (items.isEmpty() || !::dao.isInitialized) return
        items.forEach { lastPersistMs[it.id] = System.currentTimeMillis() }
        scope.launch { runCatching { dao.upsertAll(items) } }
    }

    /**
     * Persists immediately on any state-relevant field change (status,
     * error, fileName, directUrl, category); otherwise throttles to at
     * most once per [PROGRESS_PERSIST_INTERVAL_MS] per item so rapid
     * progress ticks don't hit SQLite ~5x/sec per active download.
     */
    private fun persistDebounced(item: QueueItem, previous: QueueItem?) {
        if (!::dao.isInitialized) return
        val stateChanged = previous == null ||
            previous.status != item.status ||
            previous.error != item.error ||
            previous.fileName != item.fileName ||
            previous.directUrl != item.directUrl ||
            previous.category != item.category
        val now = System.currentTimeMillis()
        val last = lastPersistMs[item.id] ?: 0L
        if (!stateChanged && now - last < PROGRESS_PERSIST_INTERVAL_MS) return
        lastPersistMs[item.id] = now
        scope.launch { runCatching { dao.upsert(item) } }
    }
}
