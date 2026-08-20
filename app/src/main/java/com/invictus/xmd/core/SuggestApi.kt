package com.invictus.xmd.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Address-bar autocomplete. Intentionally backed by DuckDuckGo's public
 * suggest endpoint rather than any list bundled in this app -- we don't
 * ship or maintain a list of sites of any kind (movie, download, or
 * otherwise). Whatever the user types is sent to DDG and whatever DDG
 * returns is shown as-is; this app has no say in the results.
 */
object SuggestApi {

    private const val ENDPOINT = "https://ac.duckduckgo.com/ac/"

    /** Empty list on any failure (network error, malformed response, blank query). */
    fun suggest(query: String, client: OkHttpClient): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val url = ENDPOINT.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", trimmed)
            ?.addQueryParameter("type", "list")
            ?.build() ?: return emptyList()

        val request = Request.Builder().url(url).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                // DDG returns e.g. ["query", ["suggestion one", "suggestion two"]]
                val outer = JSONArray(body)
                if (outer.length() < 2) return emptyList()
                val phrases = outer.getJSONArray(1)
                (0 until phrases.length()).map { phrases.getString(it) }
            }
        }.getOrDefault(emptyList())
    }
}
