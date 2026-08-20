package com.utsav.ffdownloader.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.ItemStatus
import com.utsav.ffdownloader.core.LinkParser
import com.utsav.ffdownloader.core.QueueItem
import com.utsav.ffdownloader.core.QueueRepository
import com.utsav.ffdownloader.core.ResolutionError
import com.utsav.ffdownloader.core.Settings
import com.utsav.ffdownloader.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity(), HomeFragment.Callbacks, DownloadsFragment.Callbacks {

    // ── HTTP client (resolve step) ────────────────────────────────────────
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── ChallengeActivity launcher (must be in Activity, not Fragment) ────
    private var pendingChallengeContinuation: ((directUrl: String?, error: String?) -> Unit)? = null

    private val challengeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val directUrl = result.data?.getStringExtra(ChallengeActivity.EXTRA_DIRECT_URL)
        val error     = result.data?.getStringExtra(ChallengeActivity.EXTRA_ERROR)
        pendingChallengeContinuation?.invoke(directUrl, error)
        pendingChallengeContinuation = null
    }

    // ── Storage permission (API 26-28) ────────────────────────────────────
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Storage permission denied — downloads will fail.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Items explicitly sent through the Retry button, watched until they land
    // on a terminal status. The actual download failure/success happens
    // asynchronously in DownloadService (a background coroutine, not this
    // suspend chain), so we can't just check the status right after calling
    // retrySingle() -- we have to watch QueueRepository.items for the outcome
    // and react only once, only for items the user explicitly retried.
    private val pendingRetryIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ── onCreate ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // Add fragments only on a fresh start (not after config-change)
        if (savedInstanceState == null) {
            val home      = HomeFragment()
            val downloads = DownloadsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, home,      TAG_HOME)
                .add(R.id.fragmentContainer, downloads, TAG_DOWNLOADS)
                .hide(downloads)   // Home is the initial tab
                .commit()
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showFragment(TAG_HOME)
                    supportActionBar?.title = getString(R.string.app_name)
                }
                R.id.nav_downloads -> {
                    showFragment(TAG_DOWNLOADS)
                    supportActionBar?.title = "Downloads"
                }
            }
            true
        }

        // Active-download badge on the Downloads tab
        QueueRepository.items.observe(this) { list ->
            val active = list.count {
                it.status == ItemStatus.DOWNLOADING || it.status == ItemStatus.PAUSED ||
                it.status == ItemStatus.SAVING || it.status == ItemStatus.RETRYING
            }
            val badge = bottomNav.getOrCreateBadge(R.id.nav_downloads)
            if (active > 0) {
                badge.isVisible = true
                badge.number    = active
            } else {
                badge.isVisible = false
            }
        }

        // Watches items sent through the Retry button; pops an IDM-style
        // "Link Expired" dialog (Clear / Fetch Link) the moment a retried
        // item lands back on FAILED with an expired-link error, whether that
        // failure happened at resolve-time or later during the actual
        // download.
        QueueRepository.items.observe(this) { list ->
            list.forEach { item ->
                if (item.id !in pendingRetryIds) return@forEach
                when (item.status) {
                    ItemStatus.FAILED -> {
                        pendingRetryIds.remove(item.id)
                        if (item.error?.contains("expired", ignoreCase = true) == true) {
                            showExpiredLinkDialog(item)
                        }
                    }
                    ItemStatus.DONE -> pendingRetryIds.remove(item.id) // succeeded, stop watching
                    else -> {} // still resolving/downloading -- keep watching
                }
            }
        }

        checkStoragePermission()
    }

    // ── Fragment switching ─────────────────────────────────────────────────

    private fun showFragment(tag: String) {
        val fm        = supportFragmentManager
        val home      = fm.findFragmentByTag(TAG_HOME)      ?: return
        val downloads = fm.findFragmentByTag(TAG_DOWNLOADS) ?: return
        fm.beginTransaction().apply {
            if (tag == TAG_HOME) { show(home); hide(downloads) }
            else                 { hide(home); show(downloads) }
        }.commit()
    }

    // ── HomeFragment.Callbacks ────────────────────────────────────────────

    override fun triggerPrepare(lines: List<String>) {
        lifecycleScope.launch {
            val expanded = try {
                withContext(Dispatchers.IO) { LinkParser.expandSources(lines, client) }
            } catch (e: ResolutionError) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                return@launch
            }
            QueueRepository.setLinks(expanded)
            resolveAll()
        }
    }

    override fun triggerDownloadReady() {
        DownloadService.start(this)
    }

    override fun triggerDownloadDirect(lines: List<String>) {
        QueueRepository.setLinks(lines)
        lines.forEach { link ->
            val item = QueueRepository.current().firstOrNull { it.sourceUrl == link }
            if (item != null) {
                QueueRepository.update(item.id) { it.copy(directUrl = link, status = ItemStatus.READY) }
            }
        }
        DownloadService.start(this)
    }

    // ── DownloadsFragment.Callbacks ─────────────────────────────────────────

    override fun retryItem(itemId: String) {
        val item = QueueRepository.current().firstOrNull { it.id == itemId } ?: return
        pendingRetryIds.add(itemId)
        lifecycleScope.launch { retrySingle(item) }
    }

    override fun retryAll() {
        val failed = QueueRepository.current().filter { it.status == ItemStatus.FAILED }
        if (failed.isEmpty()) return
        lifecycleScope.launch {
            for ((index, item) in failed.withIndex()) {
                retrySingle(item)
                if (index + 1 < failed.size) delay(500)
            }
        }
    }

    /**
     * Resets a failed/cancelled item and retries it. Share links (FuckingFast
     * etc.) get a fresh resolve since the previously-resolved directUrl is a
     * short-lived CDN link that may have expired by the time Retry is tapped;
     * a plain direct URL has nothing to re-resolve, so it goes straight back
     * to READY and the download service picks it up immediately.
     */
    private suspend fun retrySingle(item: QueueItem) {
        val needsResolve = LinkParser.isShareLink(item.sourceUrl)
        QueueRepository.update(item.id) {
            it.copy(
                status = if (needsResolve) ItemStatus.RESOLVING else ItemStatus.READY,
                error = null,
                bytesDone = 0L,
                bytesTotal = 0L,
                speedBps = 0.0,
                directUrl = if (needsResolve) null else (it.directUrl ?: it.sourceUrl)
            )
        }
        if (needsResolve) {
            val refreshed = QueueRepository.current().first { it.id == item.id }
            resolveOne(refreshed)
        } else {
            DownloadService.start(this@MainActivity)
        }
    }

    /**
     * IDM-style prompt shown when a retried download comes back with an
     * expired/unavailable link: "Clear" drops the item entirely, "Fetch
     * Link" retries again (re-resolving from the share link if there is
     * one) -- looping back into this same check if it expires again.
     */
    private fun showExpiredLinkDialog(item: QueueItem) {
        AlertDialog.Builder(this)
            .setTitle("Link Expired")
            .setMessage(
                "${item.fileName ?: item.sourceUrl}\n\n" +
                "This download link has expired or is no longer available."
            )
            .setPositiveButton("Fetch Link") { _, _ -> retryItem(item.id) }
            .setNegativeButton("Clear") { _, _ ->
                pendingRetryIds.remove(item.id)
                QueueRepository.removeItem(item.id)
            }
            .setCancelable(true)
            .show()
    }

    // ── Resolve logic (uses challengeLauncher — must live in Activity) ────

    private suspend fun resolveAll() {
        val items = QueueRepository.current().filter { it.status == ItemStatus.PENDING }
        for ((index, item) in items.withIndex()) {
            QueueRepository.update(item.id) { it.copy(status = ItemStatus.RESOLVING) }
            resolveOne(item)
            if (index + 1 < items.size) delay(500)
        }
    }

    private suspend fun resolveOne(item: QueueItem) {
        if (LinkParser.isGenericDownloadUrl(item.sourceUrl)) {
            QueueRepository.update(item.id) {
                it.copy(directUrl = item.sourceUrl, status = ItemStatus.READY)
            }
            return
        }
        if (!LinkParser.isShareLink(item.sourceUrl)) {
            QueueRepository.update(item.id) {
                it.copy(status = ItemStatus.FAILED, error = "Not a valid URL: ${item.sourceUrl}")
            }
            return
        }
        val fileId = try {
            LinkParser.fileId(item.sourceUrl)
        } catch (e: ResolutionError) {
            QueueRepository.update(item.id) { it.copy(status = ItemStatus.FAILED, error = e.message) }
            return
        }
        QueueRepository.update(item.id) { it.copy(status = ItemStatus.NEEDS_CHALLENGE) }

        val (directUrl, error) = suspendCancellableCoroutine<Pair<String?, String?>> { cont ->
            pendingChallengeContinuation = { url, err -> cont.resume(url to err) }
            val intent = Intent(this@MainActivity, ChallengeActivity::class.java)
                .putExtra(ChallengeActivity.EXTRA_SHARE_URL, item.sourceUrl)
                .putExtra(ChallengeActivity.EXTRA_FILE_ID,  fileId)
            challengeLauncher.launch(intent)
        }
        if (directUrl != null) {
            QueueRepository.update(item.id) { it.copy(directUrl = directUrl, status = ItemStatus.READY) }
            // If downloads are already running (or were started earlier and ran out of
            // READY items), this item would otherwise sit at READY with no worker left
            // to claim it. Re-poking the service tops workers back up to the configured
            // max so a newly-resolved link starts downloading right away.
            DownloadService.start(this@MainActivity)
        } else {
            QueueRepository.update(item.id) {
                it.copy(status = ItemStatus.FAILED, error = error ?: "Could not resolve link")
            }
        }
    }

    // ── Storage permission ────────────────────────────────────────────────

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage(
                        "This app needs 'All files access' to save downloads to the " +
                        "\"umd\" folder in your internal storage.\n\nTap Allow on the next screen."
                    )
                    .setPositiveButton("Allow") { _, _ ->
                        startActivity(
                            Intent(
                                AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.fromParts("package", packageName, null)
                            )
                        )
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        Toast.makeText(
                            this,
                            "Downloads will fail without storage permission.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    // ── Options menu ──────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) { showSettingsDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showSettingsDialog() {
        val view            = layoutInflater.inflate(R.layout.dialog_settings, null)
        val group           = view.findViewById<RadioGroup>(R.id.connectionsGroup)
        val speedInput      = view.findViewById<EditText>(R.id.speedLimitInput)
        val concurrentInput = view.findViewById<EditText>(R.id.maxConcurrentInput)
        val autoRetrySwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.autoRetrySwitch)

        val idForConnections = mapOf(
            2 to R.id.conn2, 4 to R.id.conn4, 8 to R.id.conn8, 16 to R.id.conn16
        )
        (view.findViewById<RadioButton>(
            idForConnections[Settings.connectionsPerDownload()] ?: R.id.conn4
        )).isChecked = true
        speedInput.setText(Settings.speedLimitKBps().toString())
        concurrentInput.setText(Settings.maxConcurrentDownloads().toString())
        autoRetrySwitch.isChecked = Settings.autoRetryEnabled()

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val checkedId = group.checkedRadioButtonId
                val connections = idForConnections.entries
                    .firstOrNull { it.value == checkedId }?.key ?: 4
                Settings.setConnectionsPerDownload(connections)
                Settings.setSpeedLimitKBps(speedInput.text?.toString()?.toIntOrNull() ?: 0)
                Settings.setMaxConcurrentDownloads(concurrentInput.text?.toString()?.toIntOrNull() ?: 2)
                Settings.setAutoRetryEnabled(autoRetrySwitch.isChecked)
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Constants ─────────────────────────────────────────────────────────

    companion object {
        private const val TAG_HOME      = "home"
        private const val TAG_DOWNLOADS = "downloads"
    }
}
