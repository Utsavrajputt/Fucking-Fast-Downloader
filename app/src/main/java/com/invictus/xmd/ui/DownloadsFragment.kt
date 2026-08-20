package com.invictus.xmd.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.invictus.xmd.R
import com.invictus.xmd.core.ItemStatus
import com.invictus.xmd.core.QueueItem
import com.invictus.xmd.core.QueueRepository
import com.invictus.xmd.service.DownloadService

class DownloadsFragment : Fragment() {

    /** Implemented by MainActivity -- retry needs the resolve/challenge flow that lives there. */
    interface Callbacks {
        fun retryItem(itemId: String)
        fun retryAll()
    }

    private lateinit var adapter: QueueAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_downloads, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = QueueAdapter(
            onPauseResume = { item -> onItemPauseResume(item) },
            onCancel      = { item -> DownloadService.cancelItem(requireContext(), item.id) },
            onRetry       = { item -> (activity as? Callbacks)?.retryItem(item.id) },
            onClear       = { item -> QueueRepository.removeItem(item.id) }
        )

        val recycler       = view.findViewById<RecyclerView>(R.id.queueRecycler)
        val emptyContainer = view.findViewById<View>(R.id.emptyContainer)
        val summary        = view.findViewById<TextView>(R.id.queueSummary)
        val cancelBtn       = view.findViewById<MaterialButton>(R.id.cancelButton)
        val clearAllBtn     = view.findViewById<MaterialButton>(R.id.clearAllButton)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        clearAllBtn.setOnClickListener { QueueRepository.clearFinishedAndFailed() }

        QueueRepository.items.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)

            val isEmpty = list.isEmpty()
            recycler.visibility       = if (isEmpty) View.GONE else View.VISIBLE
            emptyContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE

            if (isEmpty) {
                summary.visibility = View.GONE
                cancelBtn.visibility = View.GONE
                clearAllBtn.visibility = View.GONE
                return@observe
            }

            // ── Cancel All / Retry All -- same button slot, context-switches ──
            val hasActive = list.any {
                it.status == ItemStatus.DOWNLOADING || it.status == ItemStatus.PAUSED ||
                it.status == ItemStatus.RETRYING
            }
            val hasFailed = list.any { it.status == ItemStatus.FAILED }
            val hasClearable = list.any {
                it.status == ItemStatus.DONE || it.status == ItemStatus.FAILED
            }

            when {
                hasActive -> {
                    cancelBtn.visibility = View.VISIBLE
                    cancelBtn.text = getString(R.string.action_cancel_all)
                    val errorColor = ContextCompat.getColor(requireContext(), R.color.ff_error)
                    cancelBtn.setTextColor(errorColor)
                    cancelBtn.strokeColor = ColorStateList.valueOf(errorColor)
                    cancelBtn.setOnClickListener { DownloadService.cancelAll(requireContext()) }
                }
                hasFailed -> {
                    cancelBtn.visibility = View.VISIBLE
                    cancelBtn.text = getString(R.string.action_retry_all)
                    val accentColor = ContextCompat.getColor(requireContext(), R.color.ff_accent)
                    cancelBtn.setTextColor(accentColor)
                    cancelBtn.strokeColor = ColorStateList.valueOf(accentColor)
                    cancelBtn.setOnClickListener { (activity as? Callbacks)?.retryAll() }
                }
                else -> cancelBtn.visibility = View.GONE
            }

            clearAllBtn.visibility = if (hasClearable) View.VISIBLE else View.GONE

            val downloading = list.count { it.status == ItemStatus.DOWNLOADING }
            val ready       = list.count { it.status == ItemStatus.READY }
            val resolving   = list.count {
                it.status == ItemStatus.PENDING ||
                it.status == ItemStatus.RESOLVING ||
                it.status == ItemStatus.NEEDS_CHALLENGE
            }
            val paused  = list.count { it.status == ItemStatus.PAUSED }
            val retrying = list.count { it.status == ItemStatus.RETRYING }
            val saving  = list.count { it.status == ItemStatus.SAVING }
            val done    = list.count { it.status == ItemStatus.DONE }
            val failed  = list.count { it.status == ItemStatus.FAILED }

            val parts = mutableListOf<String>()
            if (downloading > 0) parts += "$downloading downloading"
            if (ready > 0)       parts += "$ready ready"
            if (resolving > 0)   parts += "$resolving resolving"
            if (paused > 0)      parts += "$paused paused"
            if (retrying > 0)    parts += "$retrying retrying"
            if (saving > 0)      parts += "$saving saving"
            if (done > 0)        parts += "$done done"
            if (failed > 0)      parts += "$failed failed"

            summary.text = parts.joinToString("  •  ")
            summary.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun onItemPauseResume(item: QueueItem) {
        when (item.status) {
            ItemStatus.READY -> DownloadService.start(requireContext())
            ItemStatus.PAUSED -> DownloadService.resumeItem(requireContext(), item.id)
            else -> DownloadService.pauseItem(requireContext(), item.id)
        }
    }
}
