package com.invictus.xmd.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.invictus.xmd.R
import com.invictus.xmd.core.Bookmark
import com.invictus.xmd.core.BookmarkRepository
import com.invictus.xmd.core.DnsOverHttpsResolver
import com.invictus.xmd.core.DownloadEngine
import com.invictus.xmd.core.FaviconLoader
import com.invictus.xmd.core.HistoryRepository
import com.invictus.xmd.core.LinkParser
import com.invictus.xmd.core.Settings
import com.invictus.xmd.core.SuggestApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Mini in-app browser: address bar + WebView pool, with a Chrome-style
 * speed-dial grid shown in place of the WebView on "new tab" (i.e.
 * whenever there's no URL loaded). Typing in the address bar shows
 * generic DuckDuckGo suggest results (see SuggestApi) -- no site list is
 * bundled with this app. Auto-detects fuckingfast/fitgirl links on the
 * current page and surfaces a FAB to send them to the Home download
 * queue; also intercepts any file download the page itself triggers
 * (WebView's native download signal) behind a confirm dialog.
 *
 * Each open tab owns its own WebView instance (up to [MAX_LIVE_WEBVIEWS]
 * kept alive at once, LRU-recycled beyond that -- see the "Tab pool"
 * section) instead of one WebView being re-pointed at different tabs.
 * That means switching tabs is a plain view swap (crossfaded) with no
 * reload and no restoreState() round-trip for whichever tabs are still
 * live in the pool; a tab that got evicted restores from its saved
 * WebView state on next visit instead of hitting the network again.
 *
 * The overflow (3-dot) menu is Browser-specific -- Private DNS and
 * History only, deliberately with no download-related options, kept
 * entirely separate from the app-wide download Settings dialog reachable
 * from Home/Downloads. When Private DNS isn't off, every request the
 * WebView makes (page + every sub-resource) is routed through an OkHttp
 * client using DnsOverHttpsResolver instead of the system resolver.
 */
class BrowserFragment : Fragment() {

    interface Callbacks {
        /** Same handoff HomeFragment uses for pasted links -- expands + queues + resolves. */
        fun triggerPrepare(lines: List<String>)
        /** Opens the Browser's own overflow menu (Private DNS, History) --
         *  deliberately separate from the app-wide download Settings dialog,
         *  which the Browser's overflow no longer opens. [anchor] is the
         *  3-dot button itself, so the menu can be anchored/dropped down
         *  from it Chrome-style instead of popping up as a centered dialog. */
        fun openBrowserMenu(anchor: View)
    }

    companion object {
        /** Max WebView instances kept alive across all tabs at once. Beyond
         *  this, the least-recently-used *non-current* tab's WebView is
         *  torn down (state saved first) to keep memory bounded, same
         *  general idea as Chrome's background tab discarding. */
        private const val MAX_LIVE_WEBVIEWS = 5
        private const val TAB_SWITCH_ANIM_MS = 130L
    }

    /**
     * One open tab. Each tab owns its WebView lazily -- created on first
     * navigation, possibly torn down later under pool pressure -- so a
     * pile of "New tab" entries sitting on the speed dial costs nothing.
     * [webViewState] is the WebView.saveState() snapshot taken whenever
     * this tab's WebView gets torn down (LRU eviction, or explicitly
     * reset to blank), letting a later visit restore instantly instead
     * of reloading from the network.
     */
    private data class BrowserTab(
        val id: Long,
        var url: String? = null,
        var title: String = "New tab",
        var webView: WebView? = null,
        var webViewState: android.os.Bundle? = null,
        var isLoading: Boolean = false,
        var progress: Int = 0
    )

    private lateinit var newTabButton: ImageButton
    private lateinit var urlInput: EditText
    private lateinit var reloadButton: ImageButton
    private lateinit var tabsButton: FrameLayout
    private lateinit var tabsCount: android.widget.TextView
    private lateinit var overflowButton: ImageButton
    private lateinit var pageProgress: ProgressBar
    private lateinit var siteSecurityIcon: ImageView
    private lateinit var webViewContainer: FrameLayout
    private lateinit var navLoadingVeil: View
    private lateinit var speedDialContainer: View
    private lateinit var speedDialGrid: RecyclerView
    private lateinit var addLinkFab: FloatingActionButton
    private lateinit var suggestionsCard: MaterialCardView
    private lateinit var suggestionsList: RecyclerView

    private lateinit var adapter: BookmarkAdapter
    private lateinit var suggestionAdapter: SuggestionAdapter
    private var lastDetectedLink: String? = null
    private var suggestJob: Job? = null

    private val tabs = mutableListOf(BrowserTab(id = 0L))
    private var currentTabIndex = 0
    private var nextTabId = 1L
    // Most-recently-used order of tab IDs that currently have a live
    // WebView, oldest first. Drives LRU eviction in evictIfNeeded().
    private val tabAccessOrder = mutableListOf<Long>()

    // Own client instead of reusing MainActivity's -- this is a short-timeout,
    // fire-and-forget lookup that shouldn't share connection pool pressure
    // with the resolve/download clients.
    private val suggestClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Same short-timeout shape as suggestClient, dedicated to the confirm
    // dialog's real-filename probe (see onWebViewDownloadRequested) --
    // fire-and-forget, shouldn't share pool pressure with anything else.
    private val filenameClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // ── DNS-over-HTTPS client (Browser-only; see DnsOverHttpsResolver) ─────
    // Rebuilt whenever the DNS setting changes (mode or custom URL) --
    // cheap to construct, and this keeps every subsequent request using
    // whatever the user picked without needing a restart. Null when DNS
    // mode is OFF, in which case shouldInterceptRequest below lets WebView
    // handle the request itself (system DNS) instead of intercepting.
    // shouldInterceptRequest fires on WebView's own background thread(s)
    // and can run for several sub-resources -- across potentially several
    // live tabs -- concurrently, so this cache is guarded rather than
    // plain vars, and the client itself is sized for real concurrency
    // (see currentDohClient) instead of OkHttp's default 5-per-host cap,
    // which was serializing sub-resource fetches from the same CDN host.
    @Volatile private var dohClient: OkHttpClient? = null
    @Volatile private var dohClientSignature: String? = null
    private val dohClientLock = Any()

    /** (Re)builds dohClient only if the effective DNS setting actually changed. */
    private fun currentDohClient(): OkHttpClient? {
        val mode = Settings.dnsMode()
        if (mode == Settings.DnsMode.OFF) {
            return null
        }
        val dohUrl = if (mode == Settings.DnsMode.CUSTOM) {
            Settings.dnsCustomUrl().ifBlank { DnsOverHttpsResolver.ADGUARD_DOH_URL }
        } else {
            DnsOverHttpsResolver.ADGUARD_DOH_URL
        }
        val signature = "$mode:$dohUrl"
        if (signature == dohClientSignature) return dohClient

        synchronized(dohClientLock) {
            if (signature == dohClientSignature) return dohClient
            val built = OkHttpClient.Builder()
                .dns(DnsOverHttpsResolver(dohUrl))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                // Default OkHttp concurrency (64 total / 5 per host) throttles
                // pages that pull many sub-resources from the same CDN host --
                // each shouldInterceptRequest call blocks its WebView thread
                // until the response comes back, so a tight per-host cap
                // serializes what should be parallel fetches. Raised to match
                // what a real browser keeps open per origin.
                .dispatcher(Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                })
                .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
                .build()
            dohClient = built
            dohClientSignature = signature
            return built
        }
    }

    /** Warms the DoH resolver's host cache for [url] in the background right
     *  as navigation starts, so by the time shouldInterceptRequest actually
     *  needs the address it's often already resolved instead of paying a
     *  DNS round-trip on the critical path of the very first request. */
    private fun prefetchDns(url: String) {
        val client = currentDohClient() ?: return
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { client.dns.lookup(host) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_browser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        newTabButton = view.findViewById(R.id.newTabButton)
        urlInput = view.findViewById(R.id.urlInput)
        reloadButton = view.findViewById(R.id.reloadButton)
        tabsButton = view.findViewById(R.id.tabsButton)
        tabsCount = view.findViewById(R.id.tabsCount)
        overflowButton = view.findViewById(R.id.overflowButton)
        pageProgress = view.findViewById(R.id.pageProgress)
        siteSecurityIcon = view.findViewById(R.id.siteSecurityIcon)
        webViewContainer = view.findViewById(R.id.webViewContainer)
        navLoadingVeil = view.findViewById(R.id.navLoadingVeil)
        speedDialContainer = view.findViewById(R.id.speedDialContainer)
        speedDialGrid = view.findViewById(R.id.speedDialGrid)
        addLinkFab = view.findViewById(R.id.addLinkFab)
        suggestionsCard = view.findViewById(R.id.suggestionsCard)
        suggestionsList = view.findViewById(R.id.suggestionsList)

        setupSpeedDial()
        setupAddressBar()
        setupSuggestions()

        newTabButton.setOnClickListener { addNewTab() }
        tabsButton.setOnClickListener { showTabsDialog() }
        overflowButton.setOnClickListener { (activity as? Callbacks)?.openBrowserMenu(overflowButton) }
        addLinkFab.setOnClickListener { onAddLinkClicked() }

        // Start on the speed-dial ("new tab") page.
        showSpeedDial()
        updateTabsCount()
    }

    // ── WebView pool ─────────────────────────────────────────────────────

    private fun isCurrentTab(tab: BrowserTab): Boolean = tabs.getOrNull(currentTabIndex)?.id == tab.id

    private fun touchLru(id: Long) {
        tabAccessOrder.remove(id)
        tabAccessOrder.add(id)
    }

    /** Returns [tab]'s live WebView, creating (or restoring) it if needed. */
    private fun ensureWebView(tab: BrowserTab): WebView {
        tab.webView?.let { touchLru(tab.id); return it }

        val wv = WebView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            alpha = 0f
            visibility = View.GONE
        }
        configureWebView(wv, tab)
        webViewContainer.addView(wv)
        tab.webView = wv
        touchLru(tab.id)
        evictIfNeeded()
        return wv
    }

    /** Tears down [tab]'s WebView, snapshotting its state first so a later
     *  visit can restore instantly instead of reloading from the network. */
    private fun destroyTabWebView(tab: BrowserTab) {
        tabAccessOrder.remove(tab.id)
        val wv = tab.webView ?: return
        val bundle = android.os.Bundle()
        if (wv.saveState(bundle) != null) tab.webViewState = bundle
        webViewContainer.removeView(wv)
        wv.stopLoading()
        wv.destroy()
        tab.webView = null
    }

    /** Never evicts the currently active tab, even if it's the oldest entry. */
    private fun evictIfNeeded() {
        val currentId = tabs.getOrNull(currentTabIndex)?.id
        while (tabAccessOrder.size > MAX_LIVE_WEBVIEWS) {
            val victimId = tabAccessOrder.firstOrNull { it != currentId } ?: break
            val victim = tabs.find { it.id == victimId }
            if (victim != null) destroyTabWebView(victim) else tabAccessOrder.remove(victimId)
        }
    }

    /** Fully resets [tab] to a blank "New tab" state, tearing down its WebView. */
    private fun resetTabToBlank(tab: BrowserTab) {
        destroyTabWebView(tab)
        tab.url = null
        tab.title = "New tab"
        tab.webViewState = null
        tab.isLoading = false
        tab.progress = 0
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, tab: BrowserTab) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // LOAD_DEFAULT: serve straight from cache whenever the cached
        // response is still valid per its own headers, only hitting the
        // network for stuff that's actually stale -- cache-first without
        // risking served-stale content on pages that opt out via headers.
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (isCurrentTab(tab)) onWebViewDownloadRequested(url, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                tab.url = url
                tab.isLoading = true
                tab.progress = 0
                if (isCurrentTab(tab)) {
                    pageProgress.visibility = View.VISIBLE
                    pageProgress.progress = 0
                    urlInput.setText(url)
                    updateSecurityIcon(tab)
                    reloadButton.setImageResource(R.drawable.ic_stop)
                    clearDetectedLink()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tab.isLoading = false
                val title = view.title?.takeIf { t -> t.isNotBlank() } ?: url.orEmpty()
                tab.url = url
                tab.title = title
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    HistoryRepository.record(url, title)
                }
                if (isCurrentTab(tab)) {
                    pageProgress.visibility = View.GONE
                    reloadButton.setImageResource(R.drawable.ic_refresh)
                    hideNavLoadingVeil()
                    url?.let { checkPageForLinks(it) }
                }
            }

            // Safety net: if a navigation fails outright (no connectivity, bad
            // host, etc.) onPageFinished still fires afterwards for the failed
            // load in practice, but hiding here too means the veil can never
            // get stuck up on an error path.
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                tab.isLoading = false
                if (request.isForMainFrame && isCurrentTab(tab)) {
                    hideNavLoadingVeil()
                    reloadButton.setImageResource(R.drawable.ic_refresh)
                }
            }

            /**
             * Routes every request the page makes -- the page itself and
             * every sub-resource (images, JS, CSS, XHR, etc.) -- through
             * OkHttp using DnsOverHttpsResolver, so DNS resolution follows
             * the Browser's Private DNS setting instead of the system
             * resolver. Only GET requests with no body are intercepted;
             * anything else (POST forms, main-frame navigations WebView
             * needs to handle itself for redirects/cookies/etc.) is left
             * to fall through to WebView's own network stack by returning
             * null, same as if this override didn't exist. When DNS mode
             * is OFF, currentDohClient() returns null and every request
             * falls through untouched -- zero overhead in that mode.
             */
            override fun shouldInterceptRequest(
                view: WebView, request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                if (request.method != "GET") return null
                val client = currentDohClient() ?: return null
                val url = request.url.toString()
                if (!url.startsWith("http")) return null

                return try {
                    val reqBuilder = Request.Builder().url(url)
                    request.requestHeaders.forEach { (name, value) -> reqBuilder.header(name, value) }
                    val response = client.newCall(reqBuilder.build()).execute()
                    val body = response.body
                    if (body == null) {
                        response.close()
                        return null
                    }
                    val mimeType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    val charset = body.contentType()?.charset()?.name() ?: "utf-8"
                    val responseHeaders = response.headers.toMultimap()
                        .mapValues { it.value.joinToString(", ") }
                    // WebResourceResponse requires a status code >= 100; a
                    // malformed/unexpected response code from a broken DoH
                    // path would otherwise crash the WebView renderer.
                    val statusCode = response.code.takeIf { it in 100..599 } ?: 200
                    android.webkit.WebResourceResponse(
                        mimeType, charset, statusCode,
                        response.message.ifBlank { "OK" },
                        responseHeaders, body.byteStream()
                    )
                } catch (e: Exception) {
                    // DoH lookup/connection failed for this specific request --
                    // let WebView retry it through the normal system-DNS path
                    // rather than breaking the whole page load over one asset.
                    null
                }
            }
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                if (isCurrentTab(tab)) pageProgress.progress = newProgress
            }
        }
    }

    /**
     * Called by MainActivity to consume system/gesture back presses while the
     * Browser tab is visible.
     *
     * If the current tab's WebView is showing a page, back either steps
     * through its in-page history or, with none left, resets the tab back
     * to the speed dial (still consumed). Only once we're already on the
     * speed dial does this return false, so MainActivity's callback can
     * fall back to the Home tab instead of exiting.
     */
    fun onBackPressed(): Boolean {
        val tab = tabs.getOrNull(currentTabIndex) ?: return false
        val view = tab.webView
        if (view != null && view.visibility == View.VISIBLE) {
            if (view.canGoBack()) {
                showNavLoadingVeil()
                view.goBack()
            } else {
                resetTabToBlank(tab)
                showSpeedDial()
            }
            return true
        }
        return false
    }

    // ── Address bar ──────────────────────────────────────────────────────

    private fun setupAddressBar() {
        reloadButton.setOnClickListener {
            val tab = tabs.getOrNull(currentTabIndex) ?: return@setOnClickListener
            val view = tab.webView ?: return@setOnClickListener
            if (tab.isLoading) {
                view.stopLoading()
                tab.isLoading = false
                reloadButton.setImageResource(R.drawable.ic_refresh)
                pageProgress.visibility = View.GONE
                hideNavLoadingVeil()
            } else {
                view.reload()
            }
        }

        urlInput.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                loadUrl(urlInput.text?.toString().orEmpty())
                true
            } else false
        }

        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!urlInput.hasFocus()) return // programmatic sets (e.g. onPageStarted) shouldn't trigger suggest
                scheduleSuggest(s?.toString().orEmpty())
            }
        })

        // Chrome-style collapse: while not focused, show just the domain so
        // the bar reads clean; focusing expands it back to the full URL for
        // editing (full text is always what's actually in the EditText --
        // this only swaps the *displayed* selection/cursor state on focus).
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideSuggestions()
            } else {
                urlInput.selectAll()
            }
        }
    }

    private fun setupSuggestions() {
        suggestionAdapter = SuggestionAdapter(
            onTap = { phrase -> urlInput.setText(phrase); loadUrl(phrase) },
            onAddTap = { phrase ->
                val url = normalizeToUrl(phrase)
                BookmarkRepository.add(title = phrase, url = url)
                Toast.makeText(requireContext(), R.string.bookmark_added_toast, Toast.LENGTH_SHORT).show()
            }
        )
        suggestionsList.layoutManager = LinearLayoutManager(requireContext())
        suggestionsList.adapter = suggestionAdapter
    }

    /**
     * 2-3 letters is enough to start querying, debounced ~300ms so we're not
     * firing a network request on every keystroke. Query text and results
     * come entirely from DuckDuckGo's public suggest endpoint -- nothing
     * here is a list this app ships or maintains (see SuggestApi's doc).
     */
    private fun scheduleSuggest(query: String) {
        suggestJob?.cancel()
        if (query.trim().length < 2) {
            hideSuggestions()
            return
        }
        suggestJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            val results = withContext(Dispatchers.IO) { SuggestApi.suggest(query, suggestClient) }
            if (!isAdded) return@launch
            if (results.isEmpty()) {
                hideSuggestions()
            } else {
                suggestionAdapter.submitList(results)
                suggestionsCard.visibility = View.VISIBLE
            }
        }
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        suggestionsCard.visibility = View.GONE
    }

    private fun updateSecurityIcon(tab: BrowserTab) {
        val url = tab.url
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            siteSecurityIcon.visibility = View.GONE
            return
        }
        siteSecurityIcon.visibility = View.VISIBLE
        siteSecurityIcon.setImageResource(
            if (url.startsWith("https")) R.drawable.ic_lock else R.drawable.ic_lock_open
        )
    }

    /** Syncs the shared toolbar (address text, lock icon, progress, reload/
     *  stop icon, download-link FAB) from [tab]'s own state. Call whenever
     *  [tab] becomes the active one. */
    private fun applyTabUiState(tab: BrowserTab) {
        urlInput.setText(tab.url)
        updateSecurityIcon(tab)
        pageProgress.visibility = if (tab.isLoading) View.VISIBLE else View.GONE
        pageProgress.progress = tab.progress
        reloadButton.setImageResource(if (tab.isLoading) R.drawable.ic_stop else R.drawable.ic_refresh)
        val url = tab.url
        if (url != null) checkPageForLinks(url) else clearDetectedLink()
    }

    /** Called from MainActivity (e.g. reopening a History entry) to load a
     *  URL in the current tab, same as typing it into the address bar. */
    fun openUrl(url: String) {
        loadUrl(url)
    }

    private fun loadUrl(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val url = normalizeToUrl(input)
        val tab = tabs.getOrNull(currentTabIndex) ?: return

        hideSuggestions()
        showWebView()
        tab.url = url
        tab.isLoading = true
        tab.progress = 0
        // Fresh explicit navigation -- any previously saved restore state
        // is now stale, so don't let a future pool eviction/recreate bring
        // back the old page instead of this one.
        tab.webViewState = null
        applyTabUiState(tab)
        showNavLoadingVeil()
        prefetchDns(url)

        val view = ensureWebView(tab)
        view.alpha = 1f
        view.visibility = View.VISIBLE
        view.loadUrl(url)

        // Drop keyboard focus so the address bar doesn't stay expanded.
        urlInput.clearFocus()
        val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    /** Bare host/search text -> https URL; anything already URL-shaped is passed through. */
    private fun normalizeToUrl(input: String): String {
        val looksLikeUrl = input.contains(".") && !input.contains(" ")
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            looksLikeUrl -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }
    }

    // ── Speed dial (new tab) ─────────────────────────────────────────────

    private fun setupSpeedDial() {
        adapter = BookmarkAdapter(
            onTap = { bookmark -> urlInput.setText(bookmark.url); loadUrl(bookmark.url) },
            onLongPress = { bookmark -> showBookmarkOptionsDialog(bookmark) },
            onAddTap = { showAddBookmarkDialog(prefillUrl = null) }
        )
        speedDialGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        speedDialGrid.adapter = adapter

        BookmarkRepository.bookmarks.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
        }
    }

    private fun showSpeedDial() {
        speedDialContainer.visibility = View.VISIBLE
        urlInput.setText("")
        siteSecurityIcon.visibility = View.GONE
        pageProgress.visibility = View.GONE
        reloadButton.setImageResource(R.drawable.ic_refresh)
        hideSuggestions()
        clearDetectedLink()
        hideNavLoadingVeil()
    }

    private fun showWebView() {
        speedDialContainer.visibility = View.GONE
    }

    /**
     * Covers the content area the instant we're about to actually fetch a
     * new page over the network (typed URL/search, back/forward, or a pool
     * miss on tab switch) so the outgoing page's pixels are never visible
     * mid-load. A same-pool tab switch (the common case) never shows this --
     * it just crossfades between the two already-live WebViews instead.
     * Paired with hideNavLoadingVeil(), called once the new page has
     * actually finished (or failed) loading.
     */
    private fun showNavLoadingVeil() {
        navLoadingVeil.visibility = View.VISIBLE
        navLoadingVeil.bringToFront()
    }

    private fun hideNavLoadingVeil() {
        navLoadingVeil.visibility = View.GONE
    }

    private fun showAddBookmarkDialog(prefillUrl: String?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_bookmark, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.bookmarkTitleInput)
        val urlField = dialogView.findViewById<EditText>(R.id.bookmarkUrlInput)
        urlField.setText(prefillUrl ?: tabs.getOrNull(currentTabIndex)?.url)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_bookmark_title)
            .setView(dialogView)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val url = urlField.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.bookmark_needs_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val normalized = normalizeToUrl(url)
                BookmarkRepository.add(titleInput.text?.toString()?.trim().orEmpty(), normalized)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBookmarkOptionsDialog(bookmark: Bookmark) {
        AlertDialog.Builder(requireContext())
            .setTitle(bookmark.title)
            .setItems(arrayOf(getString(R.string.edit_bookmark_title), getString(R.string.action_delete))) { _, which ->
                when (which) {
                    0 -> showEditBookmarkDialog(bookmark)
                    1 -> BookmarkRepository.remove(bookmark)
                }
            }
            .show()
    }

    private fun showEditBookmarkDialog(bookmark: Bookmark) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_bookmark, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.bookmarkTitleInput)
        val urlField = dialogView.findViewById<EditText>(R.id.bookmarkUrlInput)
        titleInput.setText(bookmark.title)
        urlField.setText(bookmark.url)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_bookmark_title)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val url = urlField.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.bookmark_needs_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BookmarkRepository.remove(bookmark)
                BookmarkRepository.add(titleInput.text?.toString()?.trim().orEmpty(), normalizeToUrl(url))
            }
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Tabs ─────────────────────────────────────────────────────────────

    private fun updateTabsCount() {
        tabsCount.text = tabs.size.toString()
    }

    private fun addNewTab() {
        val previousView = tabs.getOrNull(currentTabIndex)?.webView
        tabs.add(BrowserTab(id = nextTabId++))
        currentTabIndex = tabs.lastIndex
        previousView?.let {
            it.animate().cancel()
            it.alpha = 0f
            it.visibility = View.GONE
        }
        showSpeedDial()
        updateTabsCount()
    }

    /**
     * Switches the content area to show [index]'s tab. Since every tab owns
     * its own WebView (up to the pool cap), this is a crossfade between two
     * already-rendered views for the common case -- no reload, no
     * restoreState() -- and only falls back to a real load (with the
     * loading veil) when the tab's WebView isn't currently live, i.e. it
     * either just got LRU-evicted or has genuinely never been opened.
     *
     * [previousView] is the outgoing WebView to crossfade away from; left
     * at its default (the current tab's live WebView, if any) for a normal
     * switch, but passed explicitly as null by callers that already tore
     * down the outgoing tab themselves (e.g. closeTab) so it isn't touched
     * twice.
     */
    private fun activateTab(
        index: Int,
        previousView: WebView? = tabs.getOrNull(currentTabIndex)?.webView
    ) {
        currentTabIndex = index
        val tab = tabs[index]

        if (tab.url.isNullOrBlank()) {
            previousView?.let {
                it.animate().cancel()
                it.alpha = 0f
                it.visibility = View.GONE
            }
            showSpeedDial()
            return
        }

        showWebView()
        applyTabUiState(tab)
        val hadLiveView = tab.webView != null
        val view = ensureWebView(tab)
        if (!hadLiveView) {
            showNavLoadingVeil()
            val state = tab.webViewState
            if (state != null) {
                // Restores from WebView's own cache/history -- no network
                // round-trip, so this is still fast even on a pool miss.
                view.restoreState(state)
            } else {
                view.loadUrl(tab.url!!)
            }
        }
        crossfadeSwap(view, previousView.takeIf { it !== view })
    }

    /** Crossfades [newView] in over [oldView] (if any, and if different). */
    private fun crossfadeSwap(newView: WebView, oldView: WebView?) {
        if (oldView == null) {
            newView.animate().cancel()
            newView.alpha = 1f
            newView.visibility = View.VISIBLE
            return
        }
        newView.animate().cancel()
        newView.alpha = 0f
        newView.visibility = View.VISIBLE
        newView.animate().alpha(1f).setDuration(TAB_SWITCH_ANIM_MS).start()

        oldView.animate().cancel()
        oldView.animate().alpha(0f).setDuration(TAB_SWITCH_ANIM_MS).withEndAction {
            oldView.visibility = View.GONE
        }.start()
    }

    /**
     * Closes a tab. Never drops below one tab -- closing the last remaining
     * one just resets it to a fresh "New tab" instead of removing it, same
     * as closing the last tab in a normal browser (a new tab effectively
     * "opens" automatically since the speed dial is shown right away).
     */
    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {
            resetTabToBlank(tabs[0])
            showSpeedDial()
            updateTabsCount()
            return
        }
        val closingTab = tabs[index]
        val closingCurrent = index == currentTabIndex
        destroyTabWebView(closingTab)
        tabs.removeAt(index)
        when {
            // previousView = null: closingTab's WebView is already torn
            // down above, so activateTab shouldn't try to crossfade/hide it again.
            closingCurrent -> activateTab(index.coerceAtMost(tabs.size - 1), previousView = null)
            index < currentTabIndex -> currentTabIndex--
        }
        updateTabsCount()
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices || index == currentTabIndex) return
        activateTab(index)
    }

    private fun showTabsDialog() {
        val context = requireContext()
        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 8, 8)
        }
        val rippleBg = android.util.TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        lateinit var dialog: AlertDialog

        fun refreshRows() {
            rowsContainer.removeAllViews()
            tabs.forEachIndexed { index, tab ->
                val isActive = index == currentTabIndex
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(rippleBg)
                    if (isActive) {
                        setBackgroundColor(context.getColor(R.color.ff_surface_2))
                    }
                    isClickable = true
                    isFocusable = true
                    alpha = 0f
                    translationY = 16f
                }
                val faviconSizePx = (24 * context.resources.displayMetrics.density).toInt()
                val favicon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(faviconSizePx, faviconSizePx).apply {
                        marginStart = 8
                        marginEnd = 12
                    }
                    setImageResource(R.drawable.ic_tabs)
                    setColorFilter(context.getColor(R.color.ff_muted))
                }
                row.addView(favicon)
                tab.url?.let { url ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val bitmap = withContext(Dispatchers.IO) { FaviconLoader.load(url) }
                        if (bitmap != null) {
                            favicon.clearColorFilter()
                            favicon.setImageBitmap(bitmap)
                        }
                    }
                }
                val label = android.widget.TextView(context).apply {
                    text = tab.title.ifBlank { tab.url ?: "New tab" }
                    setTextColor(context.getColor(if (isActive) R.color.ff_accent else R.color.ff_text))
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 28, 16, 28)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val closeBtn = ImageButton(context).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setPadding(16, 16, 16, 16)
                    // Every tab is closable, including the last one -- closeTab()
                    // resets it to a fresh "New tab" (speed dial) in that case,
                    // so a new tab effectively opens automatically.
                    setOnClickListener {
                        row.animate().alpha(0f).translationX(40f).setDuration(120).withEndAction {
                            closeTab(index)
                            refreshRows()
                        }.start()
                    }
                }
                row.setOnClickListener {
                    switchToTab(index)
                    dialog.dismiss()
                }
                row.addView(label)
                row.addView(closeBtn)
                rowsContainer.addView(row)
                // Small staggered fade+rise entrance so the list doesn't just pop in.
                row.animate().alpha(1f).translationY(0f)
                    .setStartDelay((index * 24L).coerceAtMost(200L))
                    .setDuration(160)
                    .start()
            }
        }
        refreshRows()

        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.action_tabs)
            .setView(rowsContainer)
            .setPositiveButton(R.string.action_new_tab) { _, _ -> addNewTab() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    // ── Link auto-detect ─────────────────────────────────────────────────

    /**
     * Fires for ANY download the WebView's content triggers -- an <a
     * download> click, a redirect to a file with a Content-Disposition
     * header, or navigation straight to a file mimetype (apk/zip/mp4/pdf/
     * etc). This is a completely different path from checkPageForLinks:
     * that one watches the page's own URL for fuckingfast/fitgirl links
     * (site-specific, auto-shows a FAB); this one catches the browser's
     * native "start a download" signal for arbitrary files from any site.
     * Always confirms before queuing since it fires on real clicks, not
     * just heuristics.
     *
     * The contentDisposition WebView hands us here is frequently missing
     * or generic on sites like this (vcloud/gofile-style hosts serving a
     * token URL with no filename in the path) -- URLUtil.guessFileName then
     * has nothing real to work with and falls back to a mostly-made-up name
     * (e.g. "Outer.bin"). The actual filename only reliably shows up in the
     * *response's* Content-Disposition header, so show the dialog right
     * away with the best guess, then probe the URL directly and swap in
     * the real name if it resolves before the user taps a button.
     */
    private fun onWebViewDownloadRequested(url: String, contentDisposition: String?, mimeType: String?) {
        val guessedName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        var resolvedName = guessedName

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_confirm_title)
            .setMessage(getString(R.string.download_confirm_message, guessedName))
            .setPositiveButton(R.string.action_add_to_downloads) { _, _ ->
                (activity as? Callbacks)?.triggerPrepare(listOf(url))
                Toast.makeText(requireContext(), R.string.link_found_toast, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        lifecycleScope.launch {
            val probed = withContext(Dispatchers.IO) {
                DownloadEngine.probeRealFilename(filenameClient, url)
            }
            if (probed != null && probed != resolvedName && dialog.isShowing) {
                resolvedName = probed
                dialog.setMessage(getString(R.string.download_confirm_message, probed))
            }
        }
    }

    /**
     * Cheap, synchronous check against the page's own URL first (covers the
     * common case: user navigated straight to a share link or a
     * fitgirl-repacks post). We don't scrape the rendered DOM for
     * further off-URL share links here -- LinkParser.expandSources already
     * does that server-side (via Jsoup) once the link is handed to
     * triggerPrepare, so re-implementing it against WebView's DOM would be
     * redundant.
     */
    private fun checkPageForLinks(url: String) {
        if (LinkParser.isShareLink(url) || LinkParser.isFitgirlPage(url)) {
            lastDetectedLink = url
            addLinkFab.visibility = View.VISIBLE
        } else {
            clearDetectedLink()
        }
    }

    private fun clearDetectedLink() {
        lastDetectedLink = null
        addLinkFab.visibility = View.GONE
    }

    private fun onAddLinkClicked() {
        val link = lastDetectedLink ?: return
        (activity as? Callbacks)?.triggerPrepare(listOf(link))
        Toast.makeText(requireContext(), R.string.link_found_toast, Toast.LENGTH_LONG).show()
        clearDetectedLink()
    }
}
