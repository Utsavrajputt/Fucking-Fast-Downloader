package com.utsav.ffdownloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.ItemStatus
import com.utsav.ffdownloader.core.QueueItem

class QueueAdapter(
    private val onPauseResume: (QueueItem) -> Unit,
    private val onCancel: (QueueItem) -> Unit,
    private val onRetry: (QueueItem) -> Unit,
    private val onClear: (QueueItem) -> Unit
) : ListAdapter<QueueItem, QueueAdapter.VH>(DIFF) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val indicator: View      = view.findViewById(R.id.statusIndicator)
        val title: TextView      = view.findViewById(R.id.itemTitle)
        val category: TextView   = view.findViewById(R.id.itemCategory)
        val status: TextView     = view.findViewById(R.id.itemStatus)
        val sizeText: TextView   = view.findViewById(R.id.itemSizeText)   // MB done / total MB
        val progress: ProgressBar= view.findViewById(R.id.itemProgress)
        val speedEta: TextView   = view.findViewById(R.id.itemSpeedEta)   // speed + ETA
        val actions: View        = view.findViewById(R.id.itemActions)
        val pauseResume: Button  = view.findViewById(R.id.itemPauseResume)
        val cancel: Button       = view.findViewById(R.id.itemCancel)
        val secondaryActions: View = view.findViewById(R.id.itemSecondaryActions)
        val retry: Button        = view.findViewById(R.id.itemRetry)
        val clear: Button        = view.findViewById(R.id.itemClear)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        holder.title.text = item.fileName ?: item.sourceUrl
        holder.category.text = item.category.label
        holder.indicator.setBackgroundColor(context.getColor(colorForStatus(item.status)))

        // ── Status text ───────────────────────────────────────────────────
        holder.status.text = when (item.status) {
            ItemStatus.PENDING          -> "⏳ Queued"
            ItemStatus.RESOLVING        -> "🔄 Resolving…"
            ItemStatus.NEEDS_CHALLENGE  -> "🛡 Verifying — complete check in browser"
            ItemStatus.READY            -> "✅ Ready to download"
            ItemStatus.DOWNLOADING      -> {
                val pct = if (item.bytesTotal > 0) (item.bytesDone * 100 / item.bytesTotal) else 0
                "⬇  ${if (item.bytesTotal > 0) "$pct%" else "Downloading…"}"
            }
            ItemStatus.PAUSED           -> "⏸  Paused"
            ItemStatus.RETRYING         -> "🔁 ${item.error ?: "Retrying…"}"
            ItemStatus.SAVING           -> "💾 Saving to storage…"
            ItemStatus.DONE             -> "✔  Done"
            ItemStatus.FAILED           -> "✖  ${item.error ?: "Failed"}"
        }

        // ── Size line (MB done / total MB) ────────────────────────────────
        when (item.status) {
            ItemStatus.DOWNLOADING, ItemStatus.PAUSED, ItemStatus.SAVING, ItemStatus.RETRYING -> {
                when {
                    item.bytesTotal > 0 -> {
                        holder.sizeText.text =
                            "${formatBytes(item.bytesDone)} / ${formatBytes(item.bytesTotal)}"
                        holder.sizeText.visibility = View.VISIBLE
                    }
                    item.bytesDone > 0 -> {
                        holder.sizeText.text = formatBytes(item.bytesDone)
                        holder.sizeText.visibility = View.VISIBLE
                    }
                    else -> holder.sizeText.visibility = View.GONE
                }
            }
            ItemStatus.DONE -> {
                val bytes = if (item.bytesTotal > 0) item.bytesTotal else item.bytesDone
                if (bytes > 0) {
                    holder.sizeText.text = formatBytes(bytes)
                    holder.sizeText.visibility = View.VISIBLE
                } else {
                    holder.sizeText.visibility = View.GONE
                }
            }
            else -> holder.sizeText.visibility = View.GONE
        }

        // ── Progress bar ─────────────────────────────────────────────────
        when (item.status) {
            ItemStatus.DOWNLOADING -> {
                if (item.bytesTotal > 0) {
                    holder.progress.progress = ((item.bytesDone * 100) / item.bytesTotal).toInt()
                    holder.progress.visibility = View.VISIBLE
                } else {
                    holder.progress.visibility = View.GONE
                }
            }
            ItemStatus.DONE -> {
                holder.progress.progress = 100
                holder.progress.visibility = View.VISIBLE
            }
            ItemStatus.SAVING -> {
                holder.progress.progress = 100
                holder.progress.visibility = View.VISIBLE
            }
            else -> holder.progress.visibility = View.GONE
        }

        // ── Speed + ETA line ─────────────────────────────────────────────
        if (item.status == ItemStatus.DOWNLOADING && item.speedBps > 0) {
            holder.speedEta.text = buildSpeedEtaText(item)
            holder.speedEta.visibility = View.VISIBLE
        } else {
            holder.speedEta.visibility = View.GONE
        }

        // ── Action buttons ────────────────────────────────────────────────
        val showActions = item.status == ItemStatus.DOWNLOADING || item.status == ItemStatus.PAUSED ||
            item.status == ItemStatus.RETRYING
        holder.actions.visibility = if (showActions) View.VISIBLE else View.GONE
        // No live connection to pause during an auto-retry backoff wait -- only Cancel applies.
        holder.pauseResume.visibility = if (item.status == ItemStatus.RETRYING) View.GONE else View.VISIBLE
        holder.pauseResume.text = if (item.status == ItemStatus.PAUSED) {
            context.getString(R.string.action_resume)
        } else {
            context.getString(R.string.action_pause)
        }
        holder.pauseResume.setOnClickListener { onPauseResume(item) }
        holder.cancel.setOnClickListener { onCancel(item) }

        // ── Retry / Clear (FAILED gets both, DONE gets Clear only) ─────────
        val showSecondary = item.status == ItemStatus.FAILED || item.status == ItemStatus.DONE
        holder.secondaryActions.visibility = if (showSecondary) View.VISIBLE else View.GONE
        holder.retry.visibility = if (item.status == ItemStatus.FAILED) View.VISIBLE else View.GONE
        holder.retry.setOnClickListener { onRetry(item) }
        holder.clear.setOnClickListener { onClear(item) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun colorForStatus(status: ItemStatus): Int = when (status) {
        ItemStatus.PENDING,
        ItemStatus.RESOLVING,
        ItemStatus.NEEDS_CHALLENGE -> R.color.ff_muted
        ItemStatus.READY           -> R.color.ff_accent
        ItemStatus.DOWNLOADING     -> R.color.ff_accent
        ItemStatus.PAUSED          -> R.color.ff_warning
        ItemStatus.RETRYING        -> R.color.ff_warning
        ItemStatus.SAVING          -> R.color.ff_accent
        ItemStatus.DONE            -> R.color.ff_success
        ItemStatus.FAILED          -> R.color.ff_error
    }

    /** "2.1 MB/s  •  ETA 3:42" */
    private fun buildSpeedEtaText(item: QueueItem): String {
        val bps = item.speedBps
        val speedStr = when {
            bps >= 1_048_576.0 -> "%.1f MB/s".format(bps / 1_048_576.0)
            bps >= 1_024.0     -> "%.0f KB/s".format(bps / 1_024.0)
            else               -> "%.0f B/s".format(bps)
        }
        val remaining = (item.bytesTotal - item.bytesDone).coerceAtLeast(0)
        val etaSec = if (bps > 1.0 && item.bytesTotal > 0) (remaining / bps).toLong() else -1L
        return if (etaSec >= 0) "$speedStr  •  ETA ${formatDuration(etaSec)}" else speedStr
    }

    /** Bytes → human-readable string using binary prefixes (KiB, MiB, GiB). */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<QueueItem>() {
            override fun areItemsTheSame(a: QueueItem, b: QueueItem) = a.id == b.id
            override fun areContentsTheSame(a: QueueItem, b: QueueItem) = a == b
        }
    }
}
