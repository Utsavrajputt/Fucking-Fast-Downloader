package com.invictus.xmd.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences-backed settings, initialized once from FfApp.
 */
object Settings {
    private const val PREFS = "ff_settings"
    private const val KEY_CONNECTIONS = "connections_per_download"
    private const val KEY_SPEED_LIMIT_KBPS = "speed_limit_kbps"
    private const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
    private const val KEY_AUTO_RETRY = "auto_retry_network_errors"
    private const val KEY_SAVE_TO_DOWNLOADS = "save_to_downloads_folder"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun connectionsPerDownload(): Int = prefs.getInt(KEY_CONNECTIONS, 16)
    fun setConnectionsPerDownload(value: Int) {
        prefs.edit().putInt(KEY_CONNECTIONS, value).apply()
    }

    /** KB/s per individual download; 0 means unlimited. */
    fun speedLimitKBps(): Int = prefs.getInt(KEY_SPEED_LIMIT_KBPS, 0)
    fun setSpeedLimitKBps(value: Int) {
        prefs.edit().putInt(KEY_SPEED_LIMIT_KBPS, value.coerceAtLeast(0)).apply()
    }

    fun maxConcurrentDownloads(): Int = prefs.getInt(KEY_MAX_CONCURRENT, 2)
    fun setMaxConcurrentDownloads(value: Int) {
        prefs.edit().putInt(KEY_MAX_CONCURRENT, value.coerceIn(1, 5)).apply()
    }

    /** Auto-retry a failed download up to 3 times when it fails on a plain
     *  network error (timeout, connection dropped, DNS failure etc.) --
     *  never for server/link-level failures like an expired share link,
     *  those still need a manual Retry. Default ON. */
    fun autoRetryEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_RETRY, true)
    fun setAutoRetryEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RETRY, value).apply()
    }

    /** When true, downloads skip the app's own Xmd/<Category> subfolders and
     *  land flat in the device's standard Download folder instead -- same
     *  as Chrome. Default OFF (existing categorized Xmd/... behavior). */
    fun saveToDownloadsFolder(): Boolean = prefs.getBoolean(KEY_SAVE_TO_DOWNLOADS, false)
    fun setSaveToDownloadsFolder(value: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_TO_DOWNLOADS, value).apply()
    }

    // ── Browser: Private DNS (DNS-over-HTTPS for in-app browsing only) ────
    enum class DnsMode { ADGUARD, OFF, CUSTOM }

    private const val KEY_DNS_MODE = "browser_dns_mode"
    private const val KEY_DNS_CUSTOM_URL = "browser_dns_custom_url"

    fun dnsMode(): DnsMode =
        when (prefs.getString(KEY_DNS_MODE, DnsMode.ADGUARD.name)) {
            DnsMode.OFF.name -> DnsMode.OFF
            DnsMode.CUSTOM.name -> DnsMode.CUSTOM
            else -> DnsMode.ADGUARD
        }

    fun setDnsMode(value: DnsMode) {
        prefs.edit().putString(KEY_DNS_MODE, value.name).apply()
    }

    /** The DoH endpoint URL when dnsMode() == CUSTOM. Blank if never set. */
    fun dnsCustomUrl(): String = prefs.getString(KEY_DNS_CUSTOM_URL, "").orEmpty()
    fun setDnsCustomUrl(value: String) {
        prefs.edit().putString(KEY_DNS_CUSTOM_URL, value.trim()).apply()
    }
}
