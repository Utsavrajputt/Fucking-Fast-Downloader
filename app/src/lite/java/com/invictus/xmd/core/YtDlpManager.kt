package com.invictus.xmd.core

import android.content.Context
import java.io.File

/**
 * "lite" flavor stub -- this build has no youtubedl-android dependency at
 * all (see app/build.gradle.kts), so there's nothing here to wrap. Kept
 * with the exact same public API as the "full" flavor's real
 * YtDlpManager.kt so MainActivity/DownloadService (which live in the
 * shared main/ source set, built into both flavors) compile against
 * either one without any flavor-specific branching of their own beyond
 * the BuildConfig.HAS_YOUTUBE_SUPPORT check that gates ever calling these.
 */
object YtDlpManager {

    const val AUDIO_ONLY_SELECTOR = "bestaudio/best"

    data class QualityOption(
        val label: String,
        val formatSelector: String,
        val isAudioOnly: Boolean
    )

    fun standardQualityOptions(): List<QualityOption> = emptyList()

    fun isInstalled(context: Context): Boolean = false

    fun install(context: Context): String? = "This build doesn't include YouTube support"

    fun delete(context: Context) {}

    fun ensureReady(context: Context): Boolean = false

    fun isReady(): Boolean = false

    data class DownloadProgress(
        val percent: Int,
        val statusText: String?
    )

    fun download(
        url: String,
        option: QualityOption,
        outputDir: File,
        processId: String,
        context: Context,
        onProgress: (DownloadProgress) -> Unit
    ): File = throw IllegalStateException("This build doesn't include YouTube support")

    fun cancel(processId: String) {}
}
