package com.invictus.xmd.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Fetches a site's favicon for the Browser tab's speed-dial tiles.
 * No third-party image library -- a direct OkHttp GET + BitmapFactory
 * decode, with a small in-memory LRU cache so re-showing the speed dial
 * (or scrolling the grid) doesn't refetch the same host repeatedly.
 *
 * Two-step lookup per host:
 *  1. `{scheme}://{host}/favicon.ico` -- the standard location most sites
 *     serve their icon from directly.
 *  2. Google's public favicon service as a fallback for the (common) case
 *     where a site's real icon lives elsewhere (favicon.png, an SVG, a
 *     manifest-referenced path, etc.) rather than at /favicon.ico.
 * Returns null (never throws) if both fail, so callers just keep
 * showing the generic link icon already in the layout.
 */
object FaviconLoader {

    private const val MAX_CACHE_ENTRIES = 60
    private const val TARGET_PX = 96 // 2x a 52dp tile's ~48dp icon area on a xhdpi-ish screen

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {}

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** Blocking; call from a background thread/coroutine, never the main thread. */
    fun load(pageUrl: String): Bitmap? {
        val host = runCatching { URI(pageUrl).host }.getOrNull() ?: return null
        cache.get(host)?.let { return it }

        val scheme = runCatching { URI(pageUrl).scheme }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "https"
        val direct = fetch("$scheme://$host/favicon.ico")
        val bitmap = direct ?: fetch("https://www.google.com/s2/favicons?sz=64&domain=$host")

        if (bitmap != null) cache.put(host, bitmap)
        return bitmap
    }

    private fun fetch(url: String): Bitmap? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                // Downscale oversized icons (some hosts serve large PNGs at
                // /favicon.ico) so tiles don't hold huge bitmaps in memory.
                if (decoded.width > TARGET_PX * 2 || decoded.height > TARGET_PX * 2) {
                    Bitmap.createScaledBitmap(decoded, TARGET_PX, TARGET_PX, true)
                } else {
                    decoded
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
