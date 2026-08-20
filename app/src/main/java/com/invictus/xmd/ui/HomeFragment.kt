package com.invictus.xmd.ui

import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.invictus.xmd.R
import com.invictus.xmd.core.ItemStatus
import com.invictus.xmd.core.LinkParser
import com.invictus.xmd.core.QueueRepository

class HomeFragment : Fragment() {

    // ── Callback interface implemented by MainActivity ────────────────────
    interface Callbacks {
        fun triggerPrepare(lines: List<String>)
        fun triggerDownloadReady()
        fun triggerDownloadDirect(lines: List<String>)
    }

    // ── State ─────────────────────────────────────────────────────────────
    private lateinit var linksInput: EditText
    private var lastHandledClipboardText: String? = null
    private var pendingClipboardLink: String? = null

    private val clipboardManager by lazy {
        requireContext().getSystemService(ClipboardManager::class.java)
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboard()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        linksInput = view.findViewById(R.id.linksInput)

        view.findViewById<View>(R.id.prepareButton).setOnClickListener { onPrepareClicked() }
        view.findViewById<View>(R.id.downloadButton).setOnClickListener { onDownloadClicked() }
        view.findViewById<View>(R.id.clipboardAddButton).setOnClickListener { onClipboardAddClicked() }
        view.findViewById<View>(R.id.clipboardDismissButton).setOnClickListener { dismissClipboardBanner() }

        linksInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateButtonState() }
        })

        updateButtonState()

        // Quick stats: show when downloads are active so the user knows to
        // switch to the Downloads tab.
        QueueRepository.items.observe(viewLifecycleOwner) { list ->
            val statsView = view.findViewById<TextView>(R.id.quickStats)
            val downloading = list.count { it.status == ItemStatus.DOWNLOADING }
            val paused = list.count { it.status == ItemStatus.PAUSED }
            val done = list.count { it.status == ItemStatus.DONE }
            val failed = list.count { it.status == ItemStatus.FAILED }

            val parts = mutableListOf<String>()
            if (downloading > 0) parts += "⬇ $downloading downloading"
            if (paused > 0) parts += "⏸ $paused paused"
            if (done > 0) parts += "✔ $done done"
            if (failed > 0) parts += "✖ $failed failed"

            if (parts.isEmpty()) {
                statsView.visibility = View.GONE
            } else {
                statsView.text = parts.joinToString("  •  ")
                statsView.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        checkClipboard()
    }

    override fun onPause() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }

    // ── Clipboard ─────────────────────────────────────────────────────────

    /**
     * Clipboard reads only work while the app is in the foreground (Android 10+
     * privacy restriction). We show a banner so the user can tap to add the link
     * rather than auto-adding it silently.
     */
    private fun checkClipboard() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(requireContext())
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == lastHandledClipboardText) return
        if (!LinkParser.isShareLink(text) && !LinkParser.isFitgirlPage(text)) return
        if (linksInput.text?.toString()?.contains(text) == true) return
        if (QueueRepository.current().any { it.sourceUrl == text }) return

        pendingClipboardLink = text
        view?.findViewById<TextView>(R.id.clipboardBannerText)
            ?.text = getString(R.string.clipboard_link_detected, text)
        view?.findViewById<View>(R.id.clipboardBanner)?.visibility = View.VISIBLE
    }

    private fun onClipboardAddClicked() {
        val link = pendingClipboardLink ?: return
        val current = linksInput.text?.toString().orEmpty()
        linksInput.setText(if (current.isBlank()) link else "$current\n$link")
        linksInput.setSelection(linksInput.text?.length ?: 0)
        lastHandledClipboardText = link
        dismissClipboardBanner()
    }

    private fun dismissClipboardBanner() {
        lastHandledClipboardText = pendingClipboardLink ?: lastHandledClipboardText
        pendingClipboardLink = null
        view?.findViewById<View>(R.id.clipboardBanner)?.visibility = View.GONE
    }

    // ── Button state ──────────────────────────────────────────────────────

    private fun updateButtonState() {
        val lines = currentInputLines()
        val needsPrepare = lines.isEmpty() || lines.any {
            LinkParser.isShareLink(it) || LinkParser.isFitgirlPage(it)
        }
        view?.findViewById<View>(R.id.prepareButton)?.visibility =
            if (needsPrepare) View.VISIBLE else View.GONE
        view?.findViewById<MaterialButton>(R.id.downloadButton)
            ?.setText(if (needsPrepare) R.string.action_download else R.string.action_download_direct)
    }

    // ── Actions — delegated to MainActivity via Callbacks ─────────────────

    private fun onPrepareClicked() {
        val lines = currentInputLines()
        if (lines.isEmpty()) {
            Toast.makeText(requireContext(), "Paste at least one link", Toast.LENGTH_SHORT).show()
            return
        }
        (activity as? Callbacks)?.triggerPrepare(lines)
    }

    private fun onDownloadClicked() {
        val needsPrepare = view?.findViewById<View>(R.id.prepareButton)
            ?.visibility == View.VISIBLE

        if (needsPrepare) {
            val readyCount = QueueRepository.current()
                .count { it.status == ItemStatus.READY }
            if (readyCount == 0) {
                Toast.makeText(
                    requireContext(),
                    "No ready files yet — tap Prepare first",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            (activity as? Callbacks)?.triggerDownloadReady()
            linksInput.setText("")
            return
        }

        // Direct-URL fast-path: skip Prepare entirely
        val lines = currentInputLines()
        if (lines.isEmpty()) {
            Toast.makeText(requireContext(), "Paste at least one link", Toast.LENGTH_SHORT).show()
            return
        }
        (activity as? Callbacks)?.triggerDownloadDirect(lines)
        linksInput.setText("")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun currentInputLines(): List<String> =
        linksInput.text?.toString().orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
