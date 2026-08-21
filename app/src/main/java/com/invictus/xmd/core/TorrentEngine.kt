package com.invictus.xmd.core

import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What a finished (or in-progress, for naming purposes) torrent looks like. */
data class TorrentResult(
    val name: String,
    val numFiles: Int,
    val saveDir: File
)

/**
 * One shared libtorrent session for the whole process -- a real BitTorrent
 * engine (DHT, peer wire protocol, piece selection, etc.) is heavyweight
 * enough that running more than one per app would just fight itself over
 * the network and disk. Every [TorrentEngine] instance (one per in-flight
 * torrent item, same pattern as DownloadEngine for HTTP items) shares this.
 *
 * Left running for the lifetime of the process rather than stopped after
 * each download -- restarting the session (rebuilding the DHT routing
 * table, etc.) is expensive, and DownloadService already only stays alive
 * (foreground) while something is actually downloading, same as the HTTP
 * path.
 */
private object TorrentSession {
    @Volatile private var manager: SessionManager? = null

    val instance: SessionManager
        get() = manager ?: synchronized(this) {
            manager ?: SessionManager().also {
                it.start()
                manager = it
            }
        }
}

/**
 * Downloads a single torrent (from a magnet link or raw .torrent file bytes)
 * via libtorrent4j, reporting progress the same shape as [DownloadEngine]
 * so DownloadService can treat both engines interchangeably from the
 * QueueRepository's point of view (bytesDone/bytesTotal/speedBps).
 *
 * Unlike DownloadEngine this doesn't stream bytes itself -- libtorrent
 * writes pieces straight into [saveDir] on its own native thread. This
 * class's job is just: kick the torrent off, then poll its status and
 * translate that into the same progress callbacks, until it finishes /
 * is cancelled / errors out.
 */
class TorrentEngine(
    private val progress: ProgressFn = { _, _, _ -> },
    private val log: LogFn = {}
) {
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    @Volatile private var handle: TorrentHandle? = null

    fun pause() {
        paused.set(true)
        handle?.pause()
    }

    fun resume() {
        paused.set(false)
        handle?.resume()
    }

    fun cancel() {
        cancelled.set(true)
        paused.set(false)
        handle?.let { h ->
            runCatching { TorrentSession.instance.remove(h) }
        }
    }

    /**
     * Resolves a magnet link's metadata over DHT/peers (this is the slow,
     * sometimes-fails part -- a dead/unseeded magnet just times out here),
     * then downloads it into [saveDir]. Blocks the calling thread until the
     * torrent finishes, so call this from a background dispatcher.
     */
    fun downloadMagnet(magnetUri: String, saveDir: File): TorrentResult {
        cancelled.set(false)
        paused.set(false)
        saveDir.mkdirs()

        log("Looking for peers to fetch torrent metadata…")
        checkpoint()
        val data = TorrentSession.instance.fetchMagnet(magnetUri, METADATA_TIMEOUT_SECONDS)
            ?: throw RuntimeException(
                "Couldn't find this torrent's metadata (no peers/seeds responded within " +
                    "${METADATA_TIMEOUT_SECONDS}s). The magnet link may be dead."
            )
        checkpoint()

        val ti = TorrentInfo.bdecode(data)
        return startAndPoll(ti, saveDir)
    }

    /** Same as [downloadMagnet] but for a .torrent file already fetched as bytes. */
    fun downloadTorrentFile(torrentBytes: ByteArray, saveDir: File): TorrentResult {
        cancelled.set(false)
        paused.set(false)
        saveDir.mkdirs()
        val ti = TorrentInfo.bdecode(torrentBytes)
        return startAndPoll(ti, saveDir)
    }

    private fun startAndPoll(ti: TorrentInfo, saveDir: File): TorrentResult {
        log("Downloading: ${ti.name()}")
        TorrentSession.instance.download(ti, saveDir)

        val h = waitForHandle(ti) ?: throw RuntimeException("Torrent failed to start")
        handle = h

        while (true) {
            checkpoint()
            while (paused.get()) {
                Thread.sleep(200)
                checkpoint()
            }
            val status = h.status()
            val total = if (status.totalWanted() > 0) status.totalWanted() else ti.totalSize()
            progress(status.totalDone(), total, status.downloadRate().toDouble())

            val finished = status.isFinished ||
                status.state() == TorrentStatus.State.FINISHED ||
                status.state() == TorrentStatus.State.SEEDING
            if (finished) {
                progress(total, total, 0.0)
                break
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        log("Torrent finished: ${ti.name()}")
        return TorrentResult(name = ti.name(), numFiles = ti.numFiles(), saveDir = saveDir)
    }

    /** libtorrent hands back a TorrentHandle asynchronously after download() is called. */
    private fun waitForHandle(ti: TorrentInfo): TorrentHandle? {
        repeat(HANDLE_WAIT_ATTEMPTS) {
            val h = TorrentSession.instance.find(ti.infoHash())
            if (h != null) return h
            Thread.sleep(HANDLE_WAIT_INTERVAL_MS)
        }
        return TorrentSession.instance.find(ti.infoHash())
    }

    private fun checkpoint() {
        if (cancelled.get()) throw DownloadCancelledException()
    }

    companion object {
        private const val METADATA_TIMEOUT_SECONDS = 60
        private const val POLL_INTERVAL_MS = 700L
        private const val HANDLE_WAIT_ATTEMPTS = 50
        private const val HANDLE_WAIT_INTERVAL_MS = 200L
    }
}
