package com.invictus.xmd.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.invictus.xmd.R

/**
 * Shows the share page in a visible WebView so the user can clear Cloudflare
 * / Turnstile exactly as they would in the desktop app's browser window.
 * Once cleared, injected JS calls the same HTMX "/f/{id}/go" endpoint the
 * desktop app calls and returns the resulting direct URL.
 *
 * Kotlin port of ff_downloader/core/browser_resolver.py's interactive flow.
 */
class ChallengeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHARE_URL = "extra_share_url"
        const val EXTRA_FILE_ID = "extra_file_id"
        const val EXTRA_DIRECT_URL = "extra_direct_url"
        const val EXTRA_ERROR = "extra_error"
        private const val POLL_INTERVAL_MS = 1500L
        private const val TIMEOUT_MS = 120_000L
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var elapsedMs = 0L
    private var finished = false
    private lateinit var shareUrl: String
    private lateinit var fileId: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_challenge)

        shareUrl = intent.getStringExtra(EXTRA_SHARE_URL) ?: run { finishWithError("Missing URL"); return }
        fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: run { finishWithError("Missing file id"); return }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.challenge_title)
        toolbar.setTitleTextColor(getColor(R.color.ff_text))
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finishCancelled() }

        statusText = findViewById(R.id.challengeStatus)
        webView = findViewById(R.id.challengeWebView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(JsBridge(), "Native")

        statusText.text = getString(R.string.challenge_loading)
        webView.loadUrl(shareUrl)

        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onResolved(redirectUrl: String) {
            runOnUiThread { finishWithSuccess(redirectUrl) }
        }

        @JavascriptInterface
        fun onLog(message: String) {
            runOnUiThread { statusText.text = message }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (finished) return
            elapsedMs += POLL_INTERVAL_MS
            if (elapsedMs >= TIMEOUT_MS) {
                finishWithError("Timed out waiting for the challenge to clear. Try again.")
                return
            }
            webView.evaluateJavascript(POLL_SCRIPT.replace("__FILE_ID__", fileId), null)
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun finishWithSuccess(directUrl: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(pollRunnable)
        val result = Intent().putExtra(EXTRA_DIRECT_URL, directUrl)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun finishWithError(message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(pollRunnable)
        val result = Intent().putExtra(EXTRA_ERROR, message)
        setResult(RESULT_CANCELED, result)
        finish()
    }

    private fun finishCancelled() = finishWithError("Cancelled by user")

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onBackPressed() {
        finishCancelled()
    }
}

/**
 * Injected each poll tick. Mirrors _page_ready / _wait_for_turnstile / _post_go
 * from browser_resolver.py: if a challenge banner is present, do nothing
 * (user is solving it visibly). If the download widget is present and no
 * challenge remains, grab a turnstile token if one exists, then POST to the
 * HTMX "/go" endpoint and report the redirect back to Kotlin.
 */
private const val POLL_SCRIPT = """
(function() {
  try {
    var title = document.title || '';
    var body = (document.body && document.body.innerText) || '';
    var hasChallenge = title.indexOf('Just a moment') !== -1
        || body.indexOf('Verifying you are human') !== -1
        || !!document.querySelector('#challenge-running, #cf-challenge-running, .cf-browser-verification');
    if (hasChallenge) { return; }

    var hasDownload = !!document.querySelector('a.link-button')
        || !!document.querySelector('#cf-turnstile')
        || !!document.querySelector('meta[name="title"]')
        || /DOWNLOAD/i.test(body);
    if (!hasDownload) { return; }

    var tokenField = document.querySelector('[name="cf-turnstile-response"]');
    var token = window.turnstileToken || (tokenField && tokenField.value) || '';
    var hasWidget = !!document.getElementById('cf-turnstile');
    if (hasWidget && (!token || token.length <= 20)) {
      return; // widget present but not yet solved by the user
    }

    fetch('/f/__FILE_ID__/go', {
      method: 'POST',
      headers: {
        'content-type': 'application/x-www-form-urlencoded',
        'hx-request': 'true',
        'hx-current-url': location.href,
        'referer': location.href
      },
      body: new URLSearchParams({ 'cf-turnstile-response': token }).toString()
    }).then(function(response) {
      var redirect = response.headers.get('HX-Redirect') || response.headers.get('hx-redirect');
      if (redirect) {
        Native.onResolved(redirect);
      } else {
        Native.onLog('Waiting for confirmation…');
      }
    }).catch(function(err) {
      Native.onLog('Retrying…');
    });
  } catch (e) {}
})();
"""
