package com.invictus.xmd.core

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Wraps youtubedl-android (bundled yt-dlp + ffmpeg binaries) for the
 * YouTube download path -- "full" flavor only (see app/build.gradle.kts):
 * the python+ffmpeg binaries this needs can't be downloaded at runtime on
 * Android 10+ (apps targeting API 29+ can't execve() a file they wrote
 * themselves -- the W^X restriction -- so these have to ship bundled as
 * native libs, extracted by the OS installer, which is exempt), so YouTube
 * support is a separate, larger APK instead of an in-app download.
 *
 * Differs completely in shape from DownloadEngine/TorrentEngine:
 *  - yt-dlp resolves the video, downloads it, and (for qualities above what
 *    a single progressive stream offers) merges separate video+audio
 *    streams with ffmpeg, all in one call.
 *  - Progress is a 0-100 percentage from yt-dlp itself, not bytes.
 *  - Cancellation is by process id, not by closing an OkHttp Call / calling
 *    into a torrent engine handle.
 */
object YtDlpManager {

    private const val TAG = "YtDlpManager"

    /** Shared with DownloadService, which re-derives a QualityOption from a persisted QueueItem's formatSelector alone. */
    const val AUDIO_ONLY_SELECTOR = "bestaudio/best"

    @Volatile private var initialized = false

    /** Data for one row in the quality-picker dialog. */
    data class QualityOption(
        val label: String,
        /** yt-dlp `-f` format selector. */
        val formatSelector: String,
        val isAudioOnly: Boolean
    )

    /**
     * Fixed, simplified quality ladder (over the full raw yt-dlp format
     * list). Each video selector falls back gracefully to whatever's
     * actually available at or below that height -- yt-dlp doesn't error
     * out if e.g. a short/low-res video has no 1080p stream, it just picks
     * the closest match, so there's no need to probe the video's real
     * format list before showing this list.
     */
    fun standardQualityOptions(): List<QualityOption> = listOf(
        QualityOption("4K (2160p)", videoSelector(2160), isAudioOnly = false),
        QualityOption("1440p",      videoSelector(1440), isAudioOnly = false),
        QualityOption("1080p",      videoSelector(1080), isAudioOnly = false),
        QualityOption("720p",       videoSelector(720),  isAudioOnly = false),
        QualityOption("360p",       videoSelector(360),  isAudioOnly = false),
        QualityOption("144p",       videoSelector(144),  isAudioOnly = false),
        QualityOption("Audio only (MP3)", AUDIO_ONLY_SELECTOR, isAudioOnly = true)
    )

    private fun videoSelector(maxHeight: Int) =
        "bestvideo[height<=$maxHeight]+bestaudio/best[height<=$maxHeight]"

    /**
     * True once the user has tapped Install in Settings and it succeeded.
     * Nothing is unpacked automatically on app start -- [ensureReady] does
     * the (cheap, already-unpacked) re-init per process lifetime only if
     * this is true.
     */
    fun isInstalled(context: Context): Boolean = Settings.ytDlpInstalled()

    /**
     * Unpacks the bundled yt-dlp + ffmpeg binaries to internal storage.
     * Slow-ish the first time; call off the main thread. Persists the
     * "installed" flag on success so [isInstalled] survives process death.
     *
     * Returns null on success, or the failure's message/class name on
     * failure -- shown directly in Settings so a failure is diagnosable
     * without needing logcat. Catches Throwable, not just Exception -- the
     * underlying library unpacks a bundled python interpreter + native
     * ffmpeg/ffprobe binaries via internal reflection/JNI plumbing, which
     * can surface as an Error subtype (UnsatisfiedLinkError,
     * NoClassDefFoundError) rather than a plain Exception if anything about
     * that goes wrong (missing ProGuard keep rule, corrupted unpack,
     * unsupported ABI, low storage).
     */
    @Synchronized
    fun install(context: Context): String? {
        return try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            initialized = true
            Settings.setYtDlpInstalled(true)
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to install yt-dlp/ffmpeg", e)
            initialized = false
            Settings.setYtDlpInstalled(false)
            "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
    }

    /**
     * Removes the unpacked binaries to reclaim storage and flips
     * [isInstalled] back to false. The bundled assets inside the APK
     * itself aren't affected -- tapping Install again just re-unpacks them.
     */
    @Synchronized
    fun delete(context: Context) {
        initialized = false
        Settings.setYtDlpInstalled(false)
        // youtubedl-android/ffmpeg-kit unpack under the app's internal
        // files dir; matched heuristically by name rather than a hardcoded
        // path since the exact folder name isn't a stable public API.
        runCatching {
            context.filesDir?.listFiles()?.forEach { f ->
                val n = f.name.lowercase()
                if (f.isDirectory && ("youtubedl" in n || "python" in n || "ffmpeg" in n)) {
                    f.deleteRecursively()
                }
            }
        }
    }

    /**
     * Re-attaches to already-unpacked binaries at the start of a fresh
     * process (the in-memory [initialized] flag doesn't survive process
     * death, but the unpacked files on disk do) -- cheap/near-instant when
     * [isInstalled] is true, since there's nothing left to unpack.
     * Returns false without doing anything if the user never installed it.
     *
     * Also throttled-checks for a newer yt-dlp release (roughly once a day)
     * -- the bundled yt-dlp version goes stale within weeks since YouTube
     * changes its page structure often, and yt-dlp itself warns loudly (and
     * downloads can start failing) once it's more than ~90 days old. This
     * is a plain script download (yt-dlp is Python, not a compiled
     * binary), so it doesn't hit the same Android 10+ W^X restriction that
     * rules out downloading the interpreter/ffmpeg themselves at runtime.
     * Best-effort: a failed update check (e.g. no internet) doesn't block
     * the download, it just means yt-dlp isn't yet as fresh as it could be.
     */
    @Synchronized
    fun ensureReady(context: Context): Boolean {
        if (!isInstalled(context)) return false
        if (!initialized) {
            val installError = install(context)
            if (installError != null) return false
        }

        val oneDayMs = 24L * 60 * 60 * 1000
        if (System.currentTimeMillis() - Settings.ytDlpLastUpdateMs() > oneDayMs) {
            runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._NIGHTLY)
            }.onFailure { Log.w(TAG, "yt-dlp self-update check failed (will retry later)", it) }
            Settings.setYtDlpLastUpdateMs(System.currentTimeMillis())
        }
        return true
    }

    fun isReady(): Boolean = initialized

    /** One progress tick, parsed from yt-dlp's own stdout rather than trusting the library's percent-only callback alone (see parseProgressLine). */
    data class DownloadProgress(
        val percent: Int,
        /** e.g. "12.4MiB/s, ETA 00:32" -- null once nothing matches (postprocessing stages: merging, extracting audio, embedding thumbnail, etc). */
        val statusText: String?
    )

    // Matches yt-dlp's standard download-progress line, e.g.:
    // "[download]  42.5% of   10.32MiB at    1.21MiB/s ETA 00:07"
    // "[download] 100% of 3.45MiB in 00:02"
    // Percent is always present when this matches; size/speed/eta are each
    // optional since yt-dlp omits speed+ETA on the final "100% ... in ..." line.
    private val DOWNLOAD_LINE = Regex(
        """\[download]\s+([\d.]+)%(?:\s+of\s+~?\s*([\d.]+\s*\w*i?B))?(?:\s+at\s+([\d.]+\s*\w*i?B/s))?(?:\s+ETA\s+(\S+))?"""
    )

    // Postprocessing stage markers -- these lines have no percentage at all,
    // so they only feed statusText (percent stays at whatever it last was).
    private val STAGE_LINE = Regex("""^\[(Merger|ExtractAudio|Metadata|EmbedThumbnail|ThumbnailsConvertor|VideoConvertor)]\s*(.*)$""")

    /**
     * Parses one line of yt-dlp's stdout into a [DownloadProgress], or null
     * if the line has nothing progress-related in it (most lines don't --
     * yt-dlp prints a lot of [info]/[youtube] noise per run).
     *
     * This exists because youtubedl-android's own progress callback only
     * reliably fires during the raw download phase -- once yt-dlp moves into
     * postprocessing (merging video+audio, extracting/converting audio,
     * embedding the thumbnail, writing metadata), the library's regex
     * doesn't recognize those lines as progress at all, so relying on it
     * alone leaves the UI stuck showing stale/no percentage for however long
     * that phase takes. Parsing the raw line ourselves means every stage
     * shows *something* instead of a frozen "Downloading…".
     */
    private fun parseProgressLine(line: String, lastPercent: Int): DownloadProgress? {
        DOWNLOAD_LINE.find(line)?.let { m ->
            val percent = m.groupValues[1].toFloatOrNull()?.toInt()?.coerceIn(0, 100) ?: lastPercent
            val size = m.groupValues[2].trim()
            val speed = m.groupValues[3].trim()
            val eta = m.groupValues[4].trim()
            val status = buildString {
                if (speed.isNotEmpty()) append(speed)
                if (size.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(size)
                }
                if (eta.isNotEmpty() && eta != "Unknown") {
                    if (isNotEmpty()) append(" · ")
                    append("ETA $eta")
                }
            }.ifEmpty { null }
            return DownloadProgress(percent, status)
        }
        STAGE_LINE.find(line)?.let { m ->
            val stageLabel = when (m.groupValues[1]) {
                "Merger" -> "Merging video + audio…"
                "ExtractAudio" -> "Extracting audio…"
                "ThumbnailsConvertor" -> "Converting thumbnail…"
                "EmbedThumbnail" -> "Embedding thumbnail…"
                "Metadata" -> "Writing metadata…"
                "VideoConvertor" -> "Converting video…"
                else -> null
            }
            // Keep the last known download percent through postprocessing --
            // it's already effectively "done" downloading at this point, and
            // jumping back to an unknown/lower percent would look like a
            // regression to the user.
            return DownloadProgress(lastPercent.coerceAtLeast(0), stageLabel)
        }
        return null
    }

    /**
     * Downloads (and, for merged qualities, muxes) the given YouTube URL
     * straight into [outputDir] using yt-dlp's own output template, so
     * there's no separate temp-then-move step like the DIRECT path -- yt-dlp
     * already writes/renames atomically itself.
     *
     * [processId] lets [cancel] target this specific download later.
     * [onProgress] receives a percent + human-readable status parsed from
     * yt-dlp's own stdout (see [parseProgressLine]) -- more reliable across
     * the whole download+postprocess run than the library's bare percent
     * callback, which goes quiet during postprocessing stages.
     *
     * Returns the final downloaded file, discovered via yt-dlp's
     * `--print after_move:filepath`, which prints the exact on-disk path
     * once any post-processing (merge/audio-extract) is done -- more
     * reliable than trying to reconstruct the filename ourselves from the
     * video title (which can contain characters yt-dlp itself sanitizes
     * differently than our own sanitize()).
     */
    fun download(
        url: String,
        option: QualityOption,
        outputDir: File,
        processId: String,
        context: Context,
        onProgress: (DownloadProgress) -> Unit
    ): File {
        if (!ensureReady(context)) throw IllegalStateException("yt-dlp not installed")
        outputDir.mkdirs()

        val request = YoutubeDLRequest(url)
        request.addOption("-o", File(outputDir, "%(title).200B [%(id)s].%(ext)s").absolutePath)
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        request.addOption("--newline")
        request.addOption("--print", "after_move:filepath")

        if (option.isAudioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            // ID3 tags: title/uploader come from yt-dlp's own metadata for
            // free via --embed-metadata, but it maps uploader -> "artist"
            // only loosely and never sets album -- --parse-metadata fills
            // both explicitly so the file shows real Artist/Album in a
            // player instead of blank/mismatched tags. "%(artist,creator,
            // uploader)s" falls back through whichever field YouTube's
            // metadata actually has (music uploads set artist/creator;
            // regular videos usually only have uploader).
            request.addOption("--embed-metadata")
            request.addOption("--embed-thumbnail")
            // mp3 embedded art must be a JPEG (ID3v2 APIC), not yt-dlp's
            // default webp thumbnail -- ffmpeg (bundled) does this convert.
            request.addOption("--convert-thumbnails", "jpg")
            request.addOption(
                "--parse-metadata",
                "%(artist,creator,uploader,channel)s:%(meta_artist)s"
            )
            request.addOption(
                "--parse-metadata",
                "%(album,playlist_title,channel)s:%(meta_album)s"
            )
        } else {
            request.addOption("-f", option.formatSelector)
            // Merge container for the video+audio case above.
            request.addOption("--merge-output-format", "mp4")
            request.addOption("--embed-thumbnail")
            request.addOption("--embed-metadata")
        }

        var lastPercent = 0
        // Correct signature: execute(request, processId, callback) with
        // callback = (progress: Float, etaInSeconds: Long, line: String) -> Unit.
        // The library's own `progress` float is used only as a last-resort
        // fallback (it can be -1/stale during postprocessing) -- the raw
        // `line` is what actually drives percent+status via parseProgressLine.
        val response = YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            val parsed = parseProgressLine(line, lastPercent)
            if (parsed != null) {
                lastPercent = parsed.percent
                onProgress(parsed)
            } else if (progress >= 0f) {
                val fallbackPercent = progress.toInt().coerceIn(0, 100)
                lastPercent = fallbackPercent
                onProgress(DownloadProgress(fallbackPercent, null))
            }
        }

        val printedPath = response.out
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }

        val resolved = printedPath?.let { File(it) }?.takeIf { it.isFile }
            ?: outputDir.listFiles()
                ?.filter { it.isFile }
                ?.maxByOrNull { it.lastModified() }

        return resolved ?: throw RuntimeException("Download finished but the output file couldn't be located")
    }

    /** Force-stops an in-flight download started with the same [processId]. */
    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}
