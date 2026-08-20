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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
import com.invictus.xmd.core.HistoryRepository
import com.invictus.xmd.core.LinkParser
import com.invictus.xmd.core.Settings
import com.invictus.xmd.core.SuggestApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Mini in-app browser: address bar + WebView, with a Chrome-style
 * speed-dial grid shown in place of the WebView on "new tab" (i.e.
 * whenever there's no URL loaded). Typing in the address bar shows
 * generic DuckDuckGo suggest results (see SuggestApi) -- no site list is
 * bundled with this app. Auto-detects fuckingfast/fitgirl links on the
 * current page and surfaces a FAB to send them to the Home download
 * queue; also intercepts any file download the page itself triggers
 * (WebView's native download signal) behind a confirm dialog.
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

    /**
     * One open tab. A single WebView is reused across tabs rather than keeping
     * one WebView instance alive per tab -- simpler and lighter, at the cost of
     * a tab losing its scroll position/in-page state while it's not the active
     * one (it reloads [url] on switch). [title] backs the label shown in the
     * tab list.
     */
    private data class BrowserTab(
        val id: Long,
        var url: String? = null,
        var title: String = "New tab",
        // Per-tab WebView history/scroll snapshot (WebView.saveState). Lets tab
        // switches restore instantly from this instead of hitting the network
        // again via loadUrl, and keeps each tab's back/forward stack isolated
        // from the others (previously all tabs shared one WebView history,
        // so Back after switching tabs could land on a *different* tab's page).
        var webViewState: android.os.Bundle? = null
    )

    private lateinit var newTabButton: ImageButton
    private lateinit var urlInput: EditText
    private lateinit var reloadButton: ImageButton
    private lateinit var tabsButton: FrameLayout
    private lateinit var tabsCount: android.widget.TextView
    private lateinit var overflowButton: ImageButton
    private lateinit var pageProgress: ProgressBar
    private lateinit var webView: WebView
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
    // shouldInterceptRequest fires on WebView's own background thread and
    // can run for several sub-resources concurrently, so this cache is
    // guarded rather than plain vars.
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
                .build()
            dohClient = built
            dohClientSignature = signature
            return built
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
        webView = view.findViewById(R.id.webView)
        navLoadingVeil = view.findViewById(R.id.navLoadingVeil)
        speedDialContainer = view.findViewById(R.id.speedDialContainer)
        speedDialGrid = view.findViewById(R.id.speedDialGrid)
        addLinkFab = view.findViewById(R.id.addLinkFab)
        suggestionsCard = view.findViewById(R.id.suggestionsCard)
        suggestionsList = view.findViewById(R.id.suggestionsList)

        setupWebView()
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

    // ── WebView ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            onWebViewDownloadRequested(url, contentDisposition, mimeType)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageProgress.visibility = View.VISIBLE
                pageProgress.progress = 0
                url?.let { urlInput.setText(it) }
                tabs.getOrNull(currentTabIndex)?.let { it.url = url }
                clearDetectedLink()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageProgress.visibility = View.GONE
                hideNavLoadingVeil()
                val title = view.title?.takeIf { t -> t.isNotBlank() } ?: url.orEmpty()
                tabs.getOrNull(currentTabIndex)?.let {
                    it.url = url
                    it.title = title
                }
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    HistoryRepository.record(url, title)
                }
                url?.let { checkPageForLinks(it) }
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
                if (request.isForMainFrame) hideNavLoadingVeil()
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
                pageProgress.progress = newProgress
            }
        }
    }

    /**
     * Called by MainActivity to consume system/gesture back presses while the
     * Browser tab is visible.
     *
     * Previously this only handled in-page history (webView.canGoBack()) and
     * returned false otherwise -- which meant the very first navigation from
     * the speed dial (no back history yet) fell straight through to the
     * activity's default back behavior and closed the whole app instead of
     * returning to the speed dial. Now: if the WebView is showing a page,
     * back either steps through its history or, with none left, returns to
     * the speed dial (still consumed). Only once we're already on the speed
     * dial does this return false, so MainActivity's callback can fall back
     * to the Home tab instead of exiting.
     */
    fun onBackPressed(): Boolean {
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) {
                showNavLoadingVeil()
                webView.goBack()
            } else {
                showSpeedDial()
                tabs.getOrNull(currentTabIndex)?.let { it.url = null; it.title = "New tab" }
            }
            return true
        }
        return false
    }

    // ── Address bar ──────────────────────────────────────────────────────

    private fun setupAddressBar() {
        reloadButton.setOnClickListener { webView.reload() }

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

        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideSuggestions()
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

    /** Called from MainActivity (e.g. reopening a History entry) to load a
     *  URL in the current tab, same as typing it into the address bar. */
    fun openUrl(url: String) {
        showWebView()
        urlInput.setText(url)
        loadUrl(url)
    }

    private fun loadUrl(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val url = normalizeToUrl(input)
        hideSuggestions()
        showWebView()
        showNavLoadingVeil()
        webView.loadUrl(url)
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
        webView.visibility = View.GONE
        urlInput.setText("")
        hideSuggestions()
        clearDetectedLink()
        hideNavLoadingVeil()
    }

    private fun showWebView() {
        speedDialContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /**
     * Covers the WebView the instant we're about to navigate it somewhere new
     * (typed URL, back/forward, tab switch/restore) so the outgoing page's
     * pixels are never visible while the new one loads. Paired with
     * hideNavLoadingVeil(), called once the new page has actually finished
     * (or failed) loading.
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
        urlField.setText(prefillUrl ?: webView.url.takeIf { webView.visibility == View.VISIBLE })

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
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val url = urlField.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.bookmark_needs_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BookmarkRepository.remove(bookmark)
                BookmarkRepository.add(titleInput.text?.toString()?.trim().orEmpty(), normalizeToUrl(url))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Tabs ─────────────────────────────────────────────────────────────

    private fun updateTabsCount() {
        tabsCount.text = tabs.size.toString()
    }

    private fun addNewTab() {
        saveCurrentTabState()
        tabs.add(BrowserTab(id = nextTabId++))
        currentTabIndex = tabs.lastIndex
        showSpeedDial()
        updateTabsCount()
    }

    /**
     * Snapshots the currently active tab's WebView history/scroll state into
     * its BrowserTab before we navigate the shared WebView away from it.
     * Without this, switching tabs would either reload from network (slow)
     * or, worse, leave the outgoing tab's pages sitting in the *incoming*
     * tab's back/forward stack.
     */
    private fun saveCurrentTabState() {
        val tab = tabs.getOrNull(currentTabIndex) ?: return
        if (webView.visibility != View.VISIBLE || tab.url.isNullOrBlank()) return
        val bundle = android.os.Bundle()
        if (webView.saveState(bundle) != null) {
            tab.webViewState = bundle
        }
    }

    /** Switch the shared WebView to show [index], saving the outgoing tab's state first. */
    private fun switchToTab(index: Int) {
        if (index !in tabs.indices || index == currentTabIndex) return
        saveCurrentTabState()
        activateTab(index)
    }

    /**
     * Actually points the shared WebView at [index]'s content, WITHOUT saving
     * whatever the WebView is currently showing. Used by switchToTab (after it
     * has already saved the outgoing tab) and by closeTab (where the outgoing
     * content belongs to the tab that just got closed and should be discarded,
     * not saved into the tab that's about to become current).
     */
    private fun activateTab(index: Int) {
        currentTabIndex = index
        val tab = tabs[index]
        val url = tab.url
        if (url.isNullOrBlank()) {
            showSpeedDial()
        } else {
            showWebView()
            showNavLoadingVeil()
            val state = tab.webViewState
            if (state != null) {
                // Restores from WebView's own cache/history -- no network
                // round-trip, so the switch is instant instead of laggy.
                webView.restoreState(state)
            } else {
                webView.loadUrl(url)
            }
        }
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
            tabs[0] = BrowserTab(id = tabs[0].id)
            showSpeedDial()
            updateTabsCount()
            return
        }
        val closingCurrent = index == currentTabIndex
        tabs.removeAt(index)
        when {
            closingCurrent -> activateTab(index.coerceAtMost(tabs.size - 1))
            index < currentTabIndex -> currentTabIndex--
        }
        updateTabsCount()
    }

    private fun showTabsDialog() {
        val context = requireContext()
        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 8, 8)
        }

        lateinit var dialog: AlertDialog

        fun refreshRows() {
            rowsContainer.removeAllViews()
            tabs.forEachIndexed { index, tab ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val label = android.widget.TextView(context).apply {
                    text = (if (index == currentTabIndex) "\u25CF  " else "") +
                        tab.title.ifBlank { tab.url ?: "New tab" }
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 28, 16, 28)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        switchToTab(index)
                        dialog.dismiss()
                    }
                }
                val closeBtn = ImageButton(context).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setPadding(16, 16, 16, 16)
                    // Every tab is closable, including the last one -- closeTab()
                    // resets it to a fresh "New tab" (speed dial) in that case,
                    // so a new tab effectively opens automatically.
                    setOnClickListener {
                        closeTab(index)
                        refreshRows()
                    }
                }
                row.addView(label)
                row.addView(closeBtn)
                rowsContainer.addView(row)
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
