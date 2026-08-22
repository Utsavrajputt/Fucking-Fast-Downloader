package com.invictus.xmd.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.invictus.xmd.R
import com.invictus.xmd.core.ItemStatus
import com.invictus.xmd.core.LinkParser
import com.invictus.xmd.core.QueueItem
import com.invictus.xmd.core.QueueRepository
import com.invictus.xmd.core.ResolutionError
import com.invictus.xmd.core.Settings
import com.invictus.xmd.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity(), HomeFragment.Callbacks, DownloadsFragment.Callbacks, BrowserFragment.Callbacks, HistoryFragment.Callbacks {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    // ── Swipe-to-switch-tabs (bottom nav) ───────────────────────────────
    // Was previously wired into the Browser fragment's WebView (switching
    // between open website tabs there); moved here so a fast horizontal
    // swipe anywhere in the app instead switches between the Home/Browser/
    // Downloads bottom nav destinations. Living in dispatchTouchEvent (as
    // opposed to a per-view touch listener) is what lets it see swipes
    // that happen over a WebView or RecyclerView -- those views consume
    // their own touch events before a listener on a parent view would ever
    // see them, but the Activity sees every touch event first regardless.
    // Deliberately conservative (2x horizontal-over-vertical dominance + a
    // minimum distance) so it doesn't fire on ordinary vertical scrolling,
    // and it never consumes the event (super.dispatchTouchEvent still runs
    // unconditionally below), so normal scrolling/tapping/zooming is
    // completely unaffected.
    private val bottomNavSwipeOrder = listOf(R.id.nav_home, R.id.nav_browser, R.id.nav_downloads)

    private val bottomNavSwipeDetector by lazy {
        val minDistancePx = 80 * resources.displayMetrics.density
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) < minDistancePx) return false
                if (kotlin.math.abs(dx) < kotlin.math.abs(dy) * 2) return false
                val currentIndex = bottomNavSwipeOrder.indexOf(bottomNav.selectedItemId)
                if (currentIndex == -1) return false
                val step = if (dx < 0) 1 else -1
                val newIndex = (currentIndex + step).coerceIn(0, bottomNavSwipeOrder.size - 1)
                if (newIndex != currentIndex) bottomNav.selectedItemId = bottomNavSwipeOrder[newIndex]
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::bottomNav.isInitialized) bottomNavSwipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

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

    // ── Notification permission (API 33+) ──────────────────────────────────
    // Declaring POST_NOTIFICATIONS in the Manifest alone does nothing on
    // Android 13+ -- it's a runtime permission like storage/location, so
    // without an explicit request here the system silently drops every
    // notification the download-progress channel tries to post (the user
    // never even sees a system prompt, downloads just run "invisibly").
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notifications denied — you won't see download progress.",
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

    // ── onResume ──────────────────────────────────────────────────────────

    /**
     * Re-syncs the toolbar's visibility with whichever tab fragment is
     * actually showing right now. Needed because the toolbar is only ever
     * hidden/shown from inside bottomNav's tap listener (see onCreate) --
     * that's fine while the process stays alive, but if Android kills this
     * activity in the background (common on low battery / low memory) and
     * the user reopens it from Recents, onCreate reruns and the toolbar's
     * view is recreated at its default (visible) state, while
     * BottomNavigationView restores its selected tab on its own without
     * ever calling that listener -- so it was possible to come back to the
     * Browser tab with the "Xmd" toolbar wrongly showing above the
     * Browser's own address bar. Calling this every onResume (not just
     * after a fresh process start) covers both cases cheaply.
     */
    override fun onResume() {
        super.onResume()
        syncToolbarWithVisibleFragment()
    }

    private fun syncToolbarWithVisibleFragment() {
        val fm = supportFragmentManager
        val browserVisible = fm.findFragmentByTag(TAG_BROWSER)?.isHidden == false
        if (browserVisible) {
            // The Browser fragment's own address bar is the top bar here --
            // the shared app toolbar (and its title) would just duplicate it.
            toolbar.visibility = android.view.View.GONE
            return
        }
        toolbar.visibility = android.view.View.VISIBLE
        val downloadsVisible = fm.findFragmentByTag(TAG_DOWNLOADS)?.isHidden == false
        supportActionBar?.title = if (downloadsVisible) "Downloads" else getString(R.string.app_name)
    }

    // ── onCreate ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        this.toolbar = toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // Add fragments only on a fresh start (not after config-change)
        if (savedInstanceState == null) {
            val home      = HomeFragment()
            val browser   = BrowserFragment()
            val downloads = DownloadsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, home,      TAG_HOME)
                .add(R.id.fragmentContainer, browser,   TAG_BROWSER)
                .add(R.id.fragmentContainer, downloads, TAG_DOWNLOADS)
                .hide(browser)
                .hide(downloads)   // Home is the initial tab
                .commit()
        }

        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            // History is layered on top via addToBackStack (see openHistoryScreen)
            // -- showFragment() below only shows/hides the three base tab
            // fragments underneath it, so without popping first, switching
            // tabs (by tap OR the swipe gesture) left History visibly stuck
            // on top no matter which tab got selected underneath.
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
            when (item.itemId) {
                R.id.nav_home -> {
                    showFragment(TAG_HOME)
                    toolbar.visibility = android.view.View.VISIBLE
                    supportActionBar?.title = getString(R.string.app_name)
                }
                R.id.nav_browser -> {
                    showFragment(TAG_BROWSER)
                    // The Browser fragment's own address bar is the top bar here
                    // (with its own reload/tabs/overflow controls) -- the shared
                    // app toolbar (and its "Browser" title) would just duplicate it.
                    toolbar.visibility = android.view.View.GONE
                }
                R.id.nav_downloads -> {
                    showFragment(TAG_DOWNLOADS)
                    toolbar.visibility = android.view.View.VISIBLE
                    supportActionBar?.title = "Downloads"
                }
            }
            true
        }

        // Back handling, gesture or button:
        //  1. Browser tab with page history / a loaded page -> step back
        //     through it (or back to the speed dial). Handled entirely by
        //     BrowserFragment.onBackPressed().
        //  2. Browser tab already on the speed dial (or any other tab) ->
        //     jump to the Home tab, rather than falling straight through to
        //     the default behavior and closing the app. This is what used to
        //     be missing: searching or opening a site, then going back, used
        //     to exit the app outright instead of landing on Home.
        //  3. Already on the Home tab -> default behavior (exits the app).
        onBackPressedDispatcher.addCallback(this) {
            // History (or any other overlay screen added via addToBackStack)
            // is on top -- pop it first, same as it would in any other app.
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
                return@addCallback
            }
            val browser = supportFragmentManager.findFragmentByTag(TAG_BROWSER) as? BrowserFragment
            if (browser?.isVisible == true && browser.onBackPressed()) {
                return@addCallback
            }
            if (bottomNav.selectedItemId != R.id.nav_home) {
                bottomNav.selectedItemId = R.id.nav_home
                return@addCallback
            }
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
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
        checkNotificationPermission()
        autoResumePendingDownloads()
        handleIncomingIntent(intent)
    }

    // ── Incoming links (external download-manager / share target) ──────────
    // Fires when: (a) a browser's download picker launches xmd for a VIEW
    // intent on a http(s) link (see the manifest intent-filter), or (b) a
    // link is Shared into xmd from a browser that doesn't have a
    // download-manager chooser (Chrome, Samsung Internet, Edge, ...).
    // singleTop means an already-running MainActivity gets onNewIntent
    // instead of a fresh onCreate, so both entry points are covered here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty().let { text ->
                if (text.startsWith("magnet:", ignoreCase = true)) text
                else Regex("""https?://\S+""").find(text)?.value
            }
            else -> null
        }?.trim()

        val isHttp = url != null && (url.startsWith("http://") || url.startsWith("https://"))
        val isMagnet = url != null && url.startsWith("magnet:", ignoreCase = true)
        if (url.isNullOrEmpty() || !(isHttp || isMagnet)) return

        // Consume it so rotation / re-entering onResume doesn't re-queue the
        // same link a second time.
        intent.action = null
        intent.data = null

        bottomNav.selectedItemId = R.id.nav_home

        val needsPrepare = LinkParser.isShareLink(url) || LinkParser.isFitgirlPage(url)
        if (needsPrepare) {
            triggerPrepare(listOf(url))
        } else {
            triggerDownloadDirect(listOf(url))
        }
    }

    /**
     * Items that were mid-download when the process died (phone restart,
     * app killed, etc.) get rolled back to READY by
     * QueueRepository.init()'s recovery logic -- but nothing was actually
     * re-launching DownloadService to pick them back up, so they just sat
     * at "Ready to download" forever with no live worker claiming them.
     * QueueRepository.init() (called from FfApp.onCreate, before this)
     * loads persisted state asynchronously off the main thread, so give it
     * a moment to land before checking -- this only needs to happen once
     * per process, not on every config change.
     */
    private fun autoResumePendingDownloads() {
        lifecycleScope.launch {
            delay(500)
            if (QueueRepository.current().any { it.status == ItemStatus.READY }) {
                DownloadService.start(this@MainActivity)
            }
        }
    }

    // ── Fragment switching ─────────────────────────────────────────────────

    private fun showFragment(tag: String) {
        val fm        = supportFragmentManager
        val home      = fm.findFragmentByTag(TAG_HOME)      ?: return
        val browser   = fm.findFragmentByTag(TAG_BROWSER)   ?: return
        val downloads = fm.findFragmentByTag(TAG_DOWNLOADS) ?: return
        fm.beginTransaction().apply {
            when (tag) {
                TAG_HOME      -> { show(home); hide(browser); hide(downloads) }
                TAG_BROWSER   -> { hide(home); show(browser); hide(downloads) }
                else          -> { hide(home); hide(browser); show(downloads) }
            }
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
        showDownloadStartedSnackbar()
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
        showDownloadStartedSnackbar()
    }

    override fun triggerDownloadTorrentFile(uri: Uri, displayName: String?) {
        val link = uri.toString()
        QueueRepository.setLinks(listOf(link))
        val item = QueueRepository.current().firstOrNull { it.sourceUrl == link }
        if (item != null) {
            QueueRepository.update(item.id) {
                it.copy(directUrl = link, status = ItemStatus.READY, fileName = displayName ?: it.fileName)
            }
        }
        DownloadService.start(this)
        showDownloadStartedSnackbar()
    }

    /**
     * Downloads kick off in the background with no screen change, so without
     * this the user has no confirmation anything happened. Mirrors the
     * "Starting download… VIEW" pattern from stock browsers: a brief
     * Snackbar with a VIEW action that jumps straight to the Downloads tab.
     */
    private fun showDownloadStartedSnackbar() {
        Snackbar.make(
            findViewById(R.id.fragmentContainer),
            R.string.download_started_toast,
            Snackbar.LENGTH_LONG
        ).setAction(R.string.action_view) {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_downloads
        }.show()
    }

    // ── BrowserFragment.Callbacks ───────────────────────────────────────────

    // Chrome-style overflow: a PopupMenu anchored directly under the 3-dot
    // button (right-aligned via Gravity.END) instead of a centered
    // AlertDialog, so it drops down from the icon the way Chrome's overflow
    // menu does rather than looking like a generic popup.
    override fun openBrowserMenu(anchor: android.view.View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.browser_overflow_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_private_dns -> { showDnsSettingsDialog(); true }
                R.id.menu_history -> { openHistoryScreen(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showDnsSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_dns_settings, null)
        val group = dialogView.findViewById<RadioGroup>(R.id.dnsModeGroup)
        val optionAdguard = dialogView.findViewById<RadioButton>(R.id.dnsOptionAdguard)
        val optionOff = dialogView.findViewById<RadioButton>(R.id.dnsOptionOff)
        val optionCustom = dialogView.findViewById<RadioButton>(R.id.dnsOptionCustom)
        val customUrlInput = dialogView.findViewById<EditText>(R.id.dnsCustomUrlInput)

        when (Settings.dnsMode()) {
            Settings.DnsMode.ADGUARD -> optionAdguard.isChecked = true
            Settings.DnsMode.OFF -> optionOff.isChecked = true
            Settings.DnsMode.CUSTOM -> optionCustom.isChecked = true
        }
        customUrlInput.setText(Settings.dnsCustomUrl())
        customUrlInput.visibility = if (optionCustom.isChecked) android.view.View.VISIBLE else android.view.View.GONE

        group.setOnCheckedChangeListener { _, checkedId ->
            customUrlInput.visibility =
                if (checkedId == R.id.dnsOptionCustom) android.view.View.VISIBLE else android.view.View.GONE
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dns_settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                when (group.checkedRadioButtonId) {
                    R.id.dnsOptionOff -> Settings.setDnsMode(Settings.DnsMode.OFF)
                    R.id.dnsOptionCustom -> {
                        val url = customUrlInput.text?.toString()?.trim().orEmpty()
                        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                            Toast.makeText(this, R.string.dns_custom_url_needed, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        Settings.setDnsCustomUrl(url)
                        Settings.setDnsMode(Settings.DnsMode.CUSTOM)
                    }
                    else -> Settings.setDnsMode(Settings.DnsMode.ADGUARD)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openHistoryScreen() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, HistoryFragment(), TAG_HISTORY)
            .addToBackStack(TAG_HISTORY)
            .commit()
    }

    // ── HistoryFragment.Callbacks ───────────────────────────────────────────

    override fun openInBrowser(url: String) {
        // History was added via addToBackStack (see openHistoryScreen), so it's
        // still layered on top of the tab fragments here -- without popping it
        // first, tapping a history entry would open the link in Browser
        // underneath while History stayed visible on top, making it look like
        // the tap "went nowhere" / landed on the wrong screen.
        supportFragmentManager.popBackStack(TAG_HISTORY, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val browser = supportFragmentManager.findFragmentByTag(TAG_BROWSER) as? BrowserFragment
        browser?.openUrl(url)
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_browser
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
            // Same as the share-link branch below: without this, an item that
            // becomes READY after the worker pool has already exhausted the
            // queue (or was never started) sits at READY forever -- no live
            // worker left to claim it. This branch was missing the call,
            // which is why plain direct-download links (pixeldrain, hubcloud
            // generated links, etc.) got stuck on "Ready to download" while
            // share links (which do call this) downloaded fine.
            DownloadService.start(this@MainActivity)
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
                        "\"Xmd\" folder in your internal storage.\n\nTap Allow on the next screen."
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

    // ── Notification permission ─────────────────────────────────────────

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        val saveToDownloadsSwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.saveToDownloadsSwitch)

        val idForConnections = mapOf(
            2 to R.id.conn2, 4 to R.id.conn4, 8 to R.id.conn8, 16 to R.id.conn16
        )
        (view.findViewById<RadioButton>(
            idForConnections[Settings.connectionsPerDownload()] ?: R.id.conn4
        )).isChecked = true
        speedInput.setText(Settings.speedLimitKBps().toString())
        concurrentInput.setText(Settings.maxConcurrentDownloads().toString())
        autoRetrySwitch.isChecked = Settings.autoRetryEnabled()
        saveToDownloadsSwitch.isChecked = Settings.saveToDownloadsFolder()

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
                Settings.setSaveToDownloadsFolder(saveToDownloadsSwitch.isChecked)
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Constants ─────────────────────────────────────────────────────────

    companion object {
        private const val TAG_HOME      = "home"
        private const val TAG_BROWSER   = "browser"
        private const val TAG_DOWNLOADS = "downloads"
        private const val TAG_HISTORY   = "history"
    }
}
