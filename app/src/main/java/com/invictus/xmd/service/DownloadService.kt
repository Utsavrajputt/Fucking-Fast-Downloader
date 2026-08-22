package com.invictus.xmd.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.invictus.xmd.FfApp
import com.invictus.xmd.R
import com.invictus.xmd.core.CategoryDetector
import com.invictus.xmd.core.DownloadCancelledException
import com.invictus.xmd.core.DownloadCategory
import com.invictus.xmd.core.DownloadEngine
import com.invictus.xmd.core.ItemStatus
import com.invictus.xmd.core.LinkParser
import com.invictus.xmd.core.MediaPlatform
import com.invictus.xmd.core.QueueItem
import com.invictus.xmd.core.QueueRepository
import com.invictus.xmd.core.Settings
import com.invictus.xmd.core.TorrentEngine
import com.invictus.xmd.core.YtDlpManager
import com.invictus.xmd.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs the download queue with up to [Settings.maxConcurrentDownloads] items
 * downloading in parallel, each with its own independently pause/resume/
 * cancel-able DownloadEngine, showing an aggregate progress notification.
 */
class DownloadService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.invictus.xmd.action.START"
        const val ACTION_PAUSE_ITEM = "com.invictus.xmd.action.PAUSE_ITEM"
        const val ACTION_RESUME_ITEM = "com.invictus.xmd.action.RESUME_ITEM"
        const val ACTION_CANCEL_ITEM = "com.invictus.xmd.action.CANCEL_ITEM"
        const val ACTION_CANCEL_ALL = "com.invictus.xmd.action.CANCEL_ALL"
        const val EXTRA_ITEM_ID = "extra_item_id"
        private const val NOTIFICATION_ID = 42
        private const val BETWEEN_CLAIM_DELAY_MS = 500L
        private const val MAX_AUTO_RETRIES = 3

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun pauseItem(context: Context, itemId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_PAUSE_ITEM)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }

        fun resumeItem(context: Context, itemId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_RESUME_ITEM)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }

        fun cancelItem(context: Context, itemId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL_ITEM)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }

        fun cancelAll(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL))
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Force HTTP/1.1. If the server (often Cloudflare/CDN-backed, like
        // dl.fuckingfast.co) speaks HTTP/2, OkHttp will silently multiplex ALL
        // of our "parallel" segment requests over ONE physical TCP connection
        // -- so raising `connections` to 8/16 did nothing for real throughput,
        // it was still one TCP flow with one congestion window. Disabling H2
        // forces each segment onto its own genuine TCP connection, which is
        // what actually unlocks parallel bandwidth on cellular networks (this
        // is the same trick IDM / Chrome's own parallel downloader rely on).
        .protocols(listOf(Protocol.HTTP_1_1))
        // OkHttp's default Dispatcher caps concurrent requests to the SAME
        // host at 5. With up to 16 segments hitting one host, the extras
        // would queue behind the default limit instead of running in
        // parallel -- this raises the ceiling so all segments actually run
        // concurrently now that they're on separate HTTP/1.1 connections.
        .dispatcher(Dispatcher().apply {
            maxRequestsPerHost = 32
            maxRequests = 64
        })
        // Bigger pool of kept-alive connections so segment requests reuse
        // warm sockets instead of paying a fresh TCP+TLS handshake each time.
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .build()

    /** Active engines keyed by queue item id, so per-item controls can target the right download. */
    private val engines = ConcurrentHashMap<String, DownloadEngine>()

    /** Same idea as [engines], for magnet/.torrent items running through TorrentEngine instead. */
    private val torrentEngines = ConcurrentHashMap<String, TorrentEngine>()

    // Number of worker loops currently alive. Workers exit their loop the
    // moment claimNextReady() returns null (nothing READY *right now*) --
    // previously that meant a single ACTION_START only ever spun up workers
    // once, so an item that became READY *after* the workers had already
    // exhausted the queue (e.g. it was still resolving) would sit at READY
    // forever: no live worker left to claim it, and onStartCommand refused
    // to launch more because a stale `runJob` still looked "active" while
    // the other worker(s) were mid-download.
    //
    // Fix: track live worker count directly, and let every ACTION_START
    // top the count back up to Settings.maxConcurrentDownloads() -- so
    // pressing "Download ready files" again (or any other ACTION_START,
    // e.g. right after a link finishes resolving) always has a chance to
    // spawn a fresh worker for anything newly READY, even while other
    // downloads are still in flight.
    private val activeWorkers = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                topUpWorkers()
            }
            ACTION_PAUSE_ITEM -> intent.getStringExtra(EXTRA_ITEM_ID)?.let { id ->
                // yt-dlp has no native pause -- QueueAdapter already hides the
                // Pause button for YouTube items, this is just a defensive
                // no-op in case this action fires for one some other way.
                val current = QueueRepository.current().firstOrNull { it.id == id }
                if (current?.platform != MediaPlatform.YOUTUBE) {
                    engines[id]?.pause()
                    torrentEngines[id]?.pause()
                    QueueRepository.update(id) { it.copy(status = ItemStatus.PAUSED) }
                }
                updateNotification()
            }
            ACTION_RESUME_ITEM -> intent.getStringExtra(EXTRA_ITEM_ID)?.let { id ->
                engines[id]?.resume()
                torrentEngines[id]?.resume()
                QueueRepository.update(id) { it.copy(status = ItemStatus.DOWNLOADING) }
                updateNotification()
            }
            ACTION_CANCEL_ITEM -> intent.getStringExtra(EXTRA_ITEM_ID)?.let { id ->
                val current = QueueRepository.current().firstOrNull { it.id == id }
                if (current?.platform == MediaPlatform.YOUTUBE) {
                    cancelledYoutubeIds.add(id)
                    YtDlpManager.cancel(id)
                } else {
                    engines[id]?.cancel()
                    torrentEngines[id]?.cancel()
                }
                // During an auto-retry backoff wait there's no live engine (it was
                // removed before the delay), so there's nothing for .cancel() above
                // to interrupt -- mark it cancelled directly; the retry loop checks
                // this right after its delay and bails instead of trying again.
                if (current?.status == ItemStatus.RETRYING) {
                    QueueRepository.update(id) { it.copy(status = ItemStatus.FAILED, error = "Cancelled") }
                }
                updateNotification()
            }
            ACTION_CANCEL_ALL -> {
                engines.values.forEach { it.cancel() }
                torrentEngines.values.forEach { it.cancel() }
                QueueRepository.current()
                    .filter { it.platform == MediaPlatform.YOUTUBE && it.status == ItemStatus.DOWNLOADING }
                    .forEach {
                        cancelledYoutubeIds.add(it.id)
                        YtDlpManager.cancel(it.id)
                    }
                QueueRepository.current().filter { it.status == ItemStatus.RETRYING }.forEach { item ->
                    QueueRepository.update(item.id) { it.copy(status = ItemStatus.FAILED, error = "Cancelled") }
                }
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    /** Launches enough fresh worker loops to bring the live count up to the configured max. */
    private fun topUpWorkers() {
        val maxWorkers = Settings.maxConcurrentDownloads().coerceIn(1, 5)
        val toLaunch = maxWorkers - activeWorkers.get()
        if (toLaunch <= 0) return
        repeat(toLaunch) {
            activeWorkers.incrementAndGet()
            lifecycleScope.launch(Dispatchers.IO) {
                worker()
                if (activeWorkers.decrementAndGet() == 0) {
                    withContext(Dispatchers.Main) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    /** Same idea as [engines], for YouTube (yt-dlp) items -- keyed by processId (== item id). */
    private val cancelledYoutubeIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun worker() {
        while (true) {
            val item = QueueRepository.claimNextReady() ?: break
            when {
                item.platform == MediaPlatform.YOUTUBE -> downloadYoutube(item)
                LinkParser.isTorrentLink(item.sourceUrl) -> downloadTorrentOne(item.id, item.sourceUrl)
                else -> downloadOne(item.id, item.sourceUrl, item.directUrl, item.category)
            }
            kotlinx.coroutines.delay(BETWEEN_CLAIM_DELAY_MS)
        }
    }

    // ── YouTube (yt-dlp) download path ──────────────────────────────────
    /**
     * No range downloads, no resume-on-crash, no auto-retry loop here --
     * yt-dlp owns the entire resolve+download+merge process for a YouTube
     * item, and reports plain 0-100% progress instead of bytes. Kept as its
     * own function rather than shoehorned into downloadOne() above since
     * almost nothing (temp-then-move, byte progress, Content-Disposition
     * probing) actually applies to it. Full-flavor only -- see YtDlpManager.
     */
    private suspend fun downloadYoutube(item: QueueItem) {
        val itemId = item.id
        val formatSelector = item.mediaFormatSelector
        val formatLabel = item.mediaFormatLabel
        if (formatSelector == null || formatLabel == null) {
            QueueRepository.update(itemId) {
                it.copy(status = ItemStatus.FAILED, error = "No quality selected")
            }
            return
        }
        if (!YtDlpManager.isInstalled(this)) {
            // Shouldn't normally reach here since MainActivity checks this
            // before ever showing the quality picker -- but guard anyway
            // (e.g. user deleted it from Settings after the item was queued).
            QueueRepository.update(itemId) {
                it.copy(status = ItemStatus.FAILED, error = "yt-dlp not installed — install it from Settings")
            }
            return
        }

        val option = YtDlpManager.QualityOption(
            label = formatLabel,
            formatSelector = formatSelector,
            isAudioOnly = formatSelector == YtDlpManager.AUDIO_ONLY_SELECTOR
        )

        val outputDir = if (Settings.saveToDownloadsFolder()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            File(Environment.getExternalStorageDirectory(), "Xmd/${item.category.folderName}")
        }

        try {
            val file = withContext(Dispatchers.IO) {
                YtDlpManager.download(
                    url = item.sourceUrl,
                    option = option,
                    outputDir = outputDir,
                    processId = itemId,
                    context = this@DownloadService
                ) { progress ->
                    QueueRepository.update(itemId) {
                        it.copy(
                            status = ItemStatus.DOWNLOADING,
                            progressPercent = progress.percent,
                            mediaStatusText = progress.statusText
                        )
                    }
                    updateNotification()
                }
            }
            QueueRepository.update(itemId) {
                it.copy(
                    status = ItemStatus.DONE,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    progressPercent = 100,
                    mediaStatusText = null
                )
            }
        } catch (e: Throwable) {
            // Throwable (not just Exception) for the same reason as
            // YtDlpManager.install() -- the underlying library's native
            // binary invocation can surface as an Error subtype.
            val cancelled = cancelledYoutubeIds.remove(itemId)
            QueueRepository.update(itemId) {
                it.copy(
                    status = ItemStatus.FAILED,
                    error = if (cancelled) "Cancelled" else (e.message ?: "YouTube download failed"),
                    progressPercent = -1,
                    mediaStatusText = null
                )
            }
        } finally {
            cancelledYoutubeIds.remove(itemId)
            updateNotification()
        }
    }

    /**
     * Magnet / .torrent items. No connections/speed-limit settings applied
     * here yet (libtorrent has its own upload/download rate limiting knobs
     * that aren't wired up to Settings) -- straightforward "download it and
     * report progress" for now, mirroring downloadOne()'s status handling.
     */
    private suspend fun downloadTorrentOne(itemId: String, sourceUrl: String) {
        val engine = TorrentEngine(
            progress = { done, total, speed ->
                QueueRepository.update(itemId) { it.copy(bytesDone = done, bytesTotal = total, speedBps = speed) }
                updateNotification()
            },
            log = { }
        )
        torrentEngines[itemId] = engine

        try {
            val baseDir = if (Settings.saveToDownloadsFolder()) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                // Own subfolder rather than DownloadCategory.folderName -- a
                // torrent is very often a multi-file batch (a whole season,
                // an album, a repack's several parts) that belongs together
                // as one folder rather than split across Videos/Music/Others.
                File(Environment.getExternalStorageDirectory(), "Xmd/Torrents")
            }

            val result = withContext(Dispatchers.IO) {
                if (LinkParser.isMagnetLink(sourceUrl)) {
                    engine.downloadMagnet(sourceUrl, baseDir)
                } else if (sourceUrl.startsWith("content://")) {
                    // A .torrent file picked from local storage via the system file
                    // picker (HomeFragment's "Pick .torrent file" button) -- read its
                    // bytes through the ContentResolver rather than fetching over HTTP.
                    val bytes = applicationContext.contentResolver
                        .openInputStream(Uri.parse(sourceUrl))
                        ?.use { it.readBytes() }
                        ?: throw RuntimeException("Could not read the selected .torrent file")
                    engine.downloadTorrentFile(bytes, baseDir)
                } else {
                    val bytes = client.newCall(okhttp3.Request.Builder().url(sourceUrl).build())
                        .execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw RuntimeException("Could not fetch .torrent file (HTTP ${resp.code})")
                            }
                            resp.body?.bytes() ?: throw RuntimeException("Empty .torrent file")
                        }
                    engine.downloadTorrentFile(bytes, baseDir)
                }
            }

            QueueRepository.update(itemId) {
                it.copy(
                    fileName = result.name,
                    // Single-file torrent: point straight at the file so
                    // "Open" can hand it to an external app. Multi-file
                    // torrents don't have one sensible "the file" to open --
                    // filePath is left null and QueueAdapter hides Open for
                    // those (see the numFiles check on the DONE branch there
                    // isn't one currently, so this just means no usable path
                    // was ever captured -- same effect).
                    filePath = if (result.numFiles == 1) {
                        File(result.saveDir, result.name).absolutePath
                    } else null,
                    status = ItemStatus.DONE
                )
            }
        } catch (e: DownloadCancelledException) {
            QueueRepository.update(itemId) { it.copy(status = ItemStatus.FAILED, error = "Cancelled") }
        } catch (e: Exception) {
            QueueRepository.update(itemId) { it.copy(status = ItemStatus.FAILED, error = e.message ?: "Torrent download failed") }
        } finally {
            torrentEngines.remove(itemId)
            updateNotification()
        }
    }

    private suspend fun downloadOne(
        itemId: String,
        sourceUrl: String,
        directUrlAtClaim: String?,
        categoryAtClaim: DownloadCategory
    ) {
        var attempt = 0

        while (true) {
            var destinationFile: File? = null

            val engine = DownloadEngine(
                client = client,
                progress = { done, total, speed ->
                    QueueRepository.update(itemId) { it.copy(bytesDone = done, bytesTotal = total, speedBps = speed) }
                    updateNotification()
                },
                log = { },
                connections = Settings.connectionsPerDownload(),
                speedLimitBytesPerSec = Settings.speedLimitKBps().toLong() * 1024L
            )
            engines[itemId] = engine

            try {
                val directUrl = directUrlAtClaim ?: throw RuntimeException("No resolved URL")

                // The URL path alone is unreliable for id-based download endpoints
                // (pixeldrain.dev/api/file/<id>?download, hubcloud-generated links,
                // etc.) -- that path segment is just an opaque id, not the real
                // filename, which only ever appears in the response's
                // Content-Disposition header. Ask the server first; fall back to
                // the old URL/fragment-based naming if it doesn't answer with one.
                val realName = withContext(Dispatchers.IO) { DownloadEngine.probeRealFilename(client, directUrl) }
                val fileName = realName
                    ?: DownloadEngine.filenameFromLink(sourceUrl).ifBlank { DownloadEngine.filenameFromUrl(directUrl) }

                // The source URL alone (e.g. a FuckingFast share link) often has no visible
                // extension -- re-detect the category now that the real filename is resolved,
                // so it doesn't wrongly land in Others just because the share link was opaque.
                val category = CategoryDetector.detect(directUrl, hint = fileName)
                    .takeIf { it != DownloadCategory.default() } ?: categoryAtClaim
                QueueRepository.update(itemId) { it.copy(fileName = fileName, category = category) }

                // Download into the app's private cache first. Public/shared storage
                // (/sdcard/...) is served through Android's FUSE emulation layer, where
                // every read/write syscall carries extra overhead -- that overhead is
                // what was capping speed well below Chrome's. The private cache sits on
                // the real filesystem with none of that overhead, so the download itself
                // runs at full network speed. The finished file is then moved to
                // /sdcard/Xmd/ in one continuous copy, which is far faster than paying
                // the FUSE tax on every chunk of the download.
                val tempDir = File(cacheDir, "xmd_temp/${category.folderName}")
                val tempFile = File(tempDir, fileName)
                destinationFile = tempFile

                val finalDir = if (Settings.saveToDownloadsFolder()) {
                    // Chrome-style: flat, straight into the device's standard
                    // Download folder, no Xmd/<Category> subfolder at all.
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                } else {
                    File(Environment.getExternalStorageDirectory(), "Xmd/${category.folderName}")
                }
                val finalFile = File(finalDir, fileName)

                // Pause (engine.pause()) blocks in-place inside downloadAuto and never throws here --
                // the engine stays registered in `engines` so Resume can call engine.resume() on the
                // very same in-flight connection. Only a genuine Cancel throws, ending this coroutine.
                engine.downloadAuto(directUrl, tempFile)

                QueueRepository.update(itemId) { it.copy(status = ItemStatus.SAVING) }
                withContext(Dispatchers.IO) { moveToPublicStorage(tempFile, finalFile) }
                destinationFile = finalFile

                QueueRepository.update(itemId) { it.copy(status = ItemStatus.DONE, filePath = finalFile.absolutePath) }
                return
            } catch (e: DownloadCancelledException) {
                destinationFile?.delete()
                QueueRepository.update(itemId) { it.copy(status = ItemStatus.FAILED, error = "Cancelled") }
                return
            } catch (e: Exception) {
                // Only a plain network-level failure (timeout, connection dropped, DNS
                // failure, TLS handshake failure -- all surface as IOException from
                // OkHttp) is eligible for auto-retry. Server/link-level failures --
                // expired share link, bad HTTP status, incomplete segment -- are our
                // own explicit RuntimeExceptions, not IOExceptions, and deliberately
                // fall straight through to FAILED since retrying the same dead link
                // automatically would just burn battery/data for nothing; those need
                // the user's manual Retry (which can re-resolve a fresh link).
                val isNetworkError = e is IOException
                if (isNetworkError && Settings.autoRetryEnabled() && attempt < MAX_AUTO_RETRIES) {
                    attempt++
                    engines.remove(itemId)
                    QueueRepository.update(itemId) {
                        it.copy(
                            status = ItemStatus.RETRYING,
                            error = "Network error — retrying ($attempt/$MAX_AUTO_RETRIES)…"
                        )
                    }
                    updateNotification()
                    kotlinx.coroutines.delay(2_000L * attempt) // 2s, 4s, 6s backoff

                    // Cancel during the wait (no live engine to interrupt at that
                    // point) is handled by ACTION_CANCEL_ITEM/ALL setting the item
                    // to FAILED directly -- check for that here instead of blindly
                    // retrying a download the user already cancelled.
                    val stillPending = QueueRepository.current().firstOrNull { it.id == itemId }
                    if (stillPending == null || stillPending.status != ItemStatus.RETRYING) return

                    continue
                }
                QueueRepository.update(itemId) { it.copy(status = ItemStatus.FAILED, error = e.message) }
                return
            } finally {
                engines.remove(itemId)
                updateNotification()
            }
        }
    }

    /**
     * Moves the finished temp file into public storage. `renameTo` is instant
     * when both paths are on the same filesystem, but the private cache and
     * /sdcard/... often sit on different mount views (FUSE), so it commonly
     * fails there -- in which case we fall back to a large-buffer streamed
     * copy, which is still one continuous sequential write instead of the
     * many small interleaved writes a live multi-segment download would do.
     */
    private fun moveToPublicStorage(temp: File, final: File) {
        final.parentFile?.mkdirs()
        if (final.exists()) final.delete()

        if (temp.renameTo(final)) return

        FileInputStream(temp).use { input ->
            FileOutputStream(final).use { output ->
                val buffer = ByteArray(4 * 1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        temp.delete()
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val queue = QueueRepository.current()
        val active = queue.filter { it.status == ItemStatus.DOWNLOADING }
        // Paused/retrying items still need to be reflected in the notification --
        // otherwise pausing the only active download empties `active` and the
        // notification falls back to a permanent "Preparing…" + indeterminate bar.
        val relevant = queue.filter {
            it.status == ItemStatus.DOWNLOADING || it.status == ItemStatus.PAUSED ||
                it.status == ItemStatus.RETRYING
        }
        val resolving = queue.any { it.status == ItemStatus.RESOLVING }

        // yt-dlp reports a plain 0-100% instead of bytes -- excluded from the
        // byte-based sums below (mixing the two would produce meaningless
        // totals) and handled as its own case in the single-item branch.
        val byteActive = active.filter { it.platform != MediaPlatform.YOUTUBE }
        val totalDone = byteActive.sumOf { it.bytesDone }
        val totalSize = byteActive.sumOf { it.bytesTotal }
        val totalSpeed = byteActive.sumOf { it.speedBps }
        val percent = if (totalSize > 0) ((totalDone * 100) / totalSize).toInt() else 0

        val title: String
        val text: String
        // Progress bar state: only truly indeterminate while resolving with nothing
        // else going on. A paused item keeps its last known (determinate) percent.
        var indeterminate = false
        var barPercent = percent

        when {
            relevant.isEmpty() && resolving -> {
                title = getString(R.string.app_name)
                text = "Preparing…"
                indeterminate = true
            }
            relevant.isEmpty() -> {
                title = getString(R.string.app_name)
                text = "Idle"
            }
            relevant.size == 1 -> {
                val item = relevant.first()
                title = item.fileName ?: item.sourceUrl
                text = when {
                    item.status == ItemStatus.PAUSED -> "⏸  Paused — " + buildDetailLine(item.bytesDone, item.bytesTotal, 0.0)
                    item.status == ItemStatus.RETRYING -> "🔁  ${item.error ?: "Retrying…"}"
                    item.platform == MediaPlatform.YOUTUBE ->
                        (if (item.progressPercent >= 0) "${item.progressPercent}%" else "Resolving…") +
                            "  •  " + (item.mediaStatusText ?: item.mediaFormatLabel ?: "YouTube")
                    else -> buildDetailLine(item.bytesDone, item.bytesTotal, item.speedBps)
                }
                barPercent = when {
                    item.platform == MediaPlatform.YOUTUBE -> item.progressPercent.coerceAtLeast(0)
                    item.bytesTotal > 0 -> ((item.bytesDone * 100) / item.bytesTotal).toInt()
                    else -> 0
                }
            }
            else -> {
                val pausedCount = relevant.count { it.status == ItemStatus.PAUSED }
                val ytCount = relevant.count { it.platform == MediaPlatform.YOUTUBE }
                title = "${relevant.size} files" +
                    if (active.isNotEmpty()) " downloading" else " in queue"
                text = buildString {
                    append(buildDetailLine(totalDone, totalSize, totalSpeed))
                    if (pausedCount > 0) append("  •  $pausedCount paused")
                    if (ytCount > 0) append("  •  $ytCount YouTube")
                }
                val relevantByte = relevant.filter { it.platform != MediaPlatform.YOUTUBE }
                val relevantTotal = relevantByte.sumOf { it.bytesTotal }
                val relevantDone = relevantByte.sumOf { it.bytesDone }
                barPercent = if (relevantTotal > 0) ((relevantDone * 100) / relevantTotal).toInt() else 0
            }
        }

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val showBar = indeterminate || relevant.any { it.bytesTotal > 0 }
        val builder = NotificationCompat.Builder(this, FfApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (!indeterminate && showBar) "$barPercent%" else null)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, barPercent, indeterminate)
            .setContentIntent(openIntent)

        // Per-item pause/resume + a single cancel-all action -- shown whenever
        // there's a live or paused item to act on (Via-style controls right in
        // the notification).
        if (relevant.size == 1) {
            val item = relevant.first()
            if (item.status == ItemStatus.PAUSED) {
                val resumeIntent = PendingIntent.getService(
                    this, 1,
                    Intent(this, DownloadService::class.java)
                        .setAction(ACTION_RESUME_ITEM)
                        .putExtra(EXTRA_ITEM_ID, item.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, getString(R.string.action_resume), resumeIntent)
            } else if (item.status == ItemStatus.DOWNLOADING && item.platform != MediaPlatform.YOUTUBE) {
                // yt-dlp has no native pause -- see the QueueAdapter/DownloadService
                // pause-routing comments elsewhere for the same reasoning.
                val pauseIntent = PendingIntent.getService(
                    this, 1,
                    Intent(this, DownloadService::class.java)
                        .setAction(ACTION_PAUSE_ITEM)
                        .putExtra(EXTRA_ITEM_ID, item.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, getString(R.string.action_pause), pauseIntent)
            }
        }
        if (relevant.isNotEmpty()) {
            val cancelIntent = PendingIntent.getService(
                this, 2,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.action_cancel), cancelIntent)
        }

        return builder.build()
    }

    /** "12.4 MB / 45.0 MB  •  1.2 MB/s  •  ETA 0:32" */
    private fun buildDetailLine(done: Long, total: Long, speedBps: Double): String {
        val sizePart = if (total > 0) "${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done)
        if (speedBps <= 0.0) return sizePart

        val speedPart = when {
            speedBps >= 1_048_576.0 -> "%.1f MB/s".format(speedBps / 1_048_576.0)
            speedBps >= 1_024.0 -> "%.0f KB/s".format(speedBps / 1_024.0)
            else -> "%.0f B/s".format(speedBps)
        }

        val remaining = (total - done).coerceAtLeast(0)
        val etaSec = if (total > 0) (remaining / speedBps).toLong() else -1L
        val etaPart = if (etaSec >= 0) "  •  ETA ${formatDuration(etaSec)}" else ""

        return "$sizePart  •  $speedPart$etaPart"
    }

    /** Bytes → human-readable string using binary prefixes (KiB, MiB, GiB). */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
