package com.invictus.xmd.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS resolver for the in-app Browser tab only -- this never
 * touches the device's system DNS or any other traffic in the app (the
 * download engine's own OkHttpClient is untouched). Used as the [Dns]
 * for the OkHttpClient that BrowserFragment routes every WebView request
 * through (see shouldInterceptRequest), so DNS resolution for browsing
 * traffic follows whatever the user picked in the Browser's DNS setting.
 *
 * Uses the DoH JSON API (RFC 8427-ish `application/dns-json`), supported
 * by both AdGuard's public resolver and most other DoH providers, so the
 * same resolver class works for both the built-in AdGuard endpoint and
 * a user-supplied custom DoH URL.
 */
class DnsOverHttpsResolver(private val dohUrl: String) : Dns {

    companion object {
        const val ADGUARD_DOH_URL = "https://dns.adguard.com/dns-query"
    }

    // Separate, short-timeout client for the DoH lookups themselves --
    // deliberately not the same client whose Dns this is plugged into,
    // to avoid any recursive resolution weirdness.
    private val lookupClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // Small in-memory cache so repeated requests to the same host during a
    // browsing session (every sub-resource on a page) don't each trigger a
    // fresh DoH round-trip. No persistence, no TTL bookkeeping -- cleared
    // when the process dies, which is fine for a browsing-session cache.
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { return it }

        val resolved = runCatching { queryDoh(hostname, type = "A") }.getOrDefault(emptyList())
            .ifEmpty { runCatching { queryDoh(hostname, type = "AAAA") }.getOrDefault(emptyList()) }

        if (resolved.isEmpty()) {
            // DoH gave nothing usable (provider down, hostname genuinely
            // doesn't resolve, etc.) -- fall back to the system resolver
            // rather than failing the whole page load.
            return runCatching { Dns.SYSTEM.lookup(hostname) }
                .getOrElse { throw UnknownHostException(hostname) }
        }

        cache[hostname] = resolved
        return resolved
    }

    private fun queryDoh(hostname: String, type: String): List<InetAddress> {
        val url = dohUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", type)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()

        lookupClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            val addresses = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val entry = answers.getJSONObject(i)
                // type 1 = A, 28 = AAAA -- only take address records, DoH
                // responses can interleave CNAME (type 5) entries too.
                if (entry.optInt("type") !in setOf(1, 28)) continue
                val ip = entry.optString("data").takeIf { it.isNotBlank() } ?: continue
                runCatching { InetAddress.getByName(ip) }.getOrNull()?.let { addresses.add(it) }
            }
            return addresses
        }
    }
}
