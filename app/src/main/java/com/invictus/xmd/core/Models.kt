package com.invictus.xmd.core

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Status of a single queued link as it moves through resolve -> download. */
enum class ItemStatus {
    PENDING,
    RESOLVING,
    NEEDS_CHALLENGE,
    READY,
    DOWNLOADING,
    PAUSED,
    RETRYING,
    SAVING,
    DONE,
    FAILED
}

/**
 * Where a queue item's bytes actually come from. DIRECT covers everything
 * the app already handled (FuckingFast share links, generic direct URLs,
 * fitgirl-expanded links, magnet/.torrent via TorrentEngine) via their own
 * existing engines. YOUTUBE is downloaded/merged by yt-dlp itself (see
 * YtDlpManager) instead -- no directUrl, its own percent-based progress.
 * Deliberately left room to grow (INSTAGRAM, TERABOX, ...) since they'll
 * likely follow the same "external extractor, percent progress" shape.
 */
enum class MediaPlatform {
    DIRECT,
    YOUTUBE
}

/**
 * User-facing download category. Each maps to its own subfolder under the
 * app's downloads directory (auto-created on first download in that
 * category). Auto-detected per link from its file extension by
 * [com.invictus.xmd.core.CategoryDetector] -- not user-picked
 * anymore (IDM-style auto-categorization), stored on each [QueueItem] so
 * DownloadService knows where to save it.
 */
enum class DownloadCategory(val folderName: String, val label: String) {
    VIDEOS("Videos", "Videos"),
    MUSIC("Music", "Music"),
    DOCUMENTS("Documents", "Documents"),
    APPS("Apps", "Apps"),
    OTHERS("Others", "Others");

    companion object {
        fun default() = OTHERS
    }
}

/**
 * One entry in the queue. [sourceUrl] is what the user pasted (or a link
 * discovered on a fitgirl-repacks page); [directUrl] is filled in once
 * resolved to a dl.fuckingfast.co URL.
 *
 * Persisted to disk via Room (see core/db/AppDatabase.kt) so the queue
 * survives the app process being killed/restarted -- QueueRepository used
 * to hold this purely in memory, which meant every item vanished on
 * restart even though the downloaded files themselves were fine.
 */
@Entity(tableName = "queue_items")
data class QueueItem(
    @PrimaryKey
    val id: String,
    val sourceUrl: String,
    var directUrl: String? = null,
    var status: ItemStatus = ItemStatus.PENDING,
    var fileName: String? = null,
    var error: String? = null,
    var bytesDone: Long = 0L,
    var bytesTotal: Long = 0L,
    var speedBps: Double = 0.0,
    var downloadStartedAtMs: Long = 0L,
    var category: DownloadCategory = DownloadCategory.default(),

    // ── YouTube (yt-dlp) specific fields, unused for MediaPlatform.DIRECT ──
    var platform: MediaPlatform = MediaPlatform.DIRECT,
    /** yt-dlp `-f` format selector chosen in the quality picker, e.g. "bestvideo[height<=1080]+bestaudio/best[height<=1080]". */
    var mediaFormatSelector: String? = null,
    /** Display label for the chosen quality, e.g. "1080p" or "Audio (MP3)". */
    var mediaFormatLabel: String? = null,
    /**
     * yt-dlp reports progress as a 0-100 percentage, not bytes -- -1 means
     * "not applicable, use bytesDone/bytesTotal instead" (the DIRECT path).
     */
    var progressPercent: Int = -1
)

class ResolutionError(message: String) : Exception(message)
class DownloadCancelledException(message: String = "Download cancelled") : Exception(message)
