package com.invictus.xmd.core

import java.util.Locale

/**
 * Auto-categorizes a link/filename into a [DownloadCategory] by file
 * extension, IDM-style -- no manual picker. Checked against the pasted
 * URL's path first; DownloadService re-checks against the resolved
 * filename (Content-Disposition / final URL) once that's known, in case
 * the extension wasn't visible in the original share/short link.
 */
object CategoryDetector {

    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts"
    )
    private val MUSIC_EXT = setOf(
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "aiff"
    )
    private val DOCUMENT_EXT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "rtf", "odt"
    )
    private val APP_EXT = setOf(
        "apk", "exe", "msi", "dmg", "deb", "rpm", "appimage"
    )

    /**
     * [hint] is checked first when given (e.g. a resolved filename from
     * Content-Disposition), falling back to [url]'s own path -- covers
     * short/share links whose extension only becomes known after resolve.
     */
    fun detect(url: String, hint: String? = null): DownloadCategory {
        val ext = extensionOf(hint) ?: extensionOf(url) ?: return DownloadCategory.default()
        return when (ext) {
            in VIDEO_EXT -> DownloadCategory.VIDEOS
            in MUSIC_EXT -> DownloadCategory.MUSIC
            in DOCUMENT_EXT -> DownloadCategory.DOCUMENTS
            in APP_EXT -> DownloadCategory.APPS
            else -> DownloadCategory.default()
        }
    }

    private fun extensionOf(value: String?): String? {
        if (value.isNullOrBlank()) return null
        // Strip query string / fragment so "video.mp4?token=..." resolves correctly.
        val path = value.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return null
        return name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it.length <= 6 } // guard against "1.2.3.4"-style false positives
    }
}
