package com.invictus.xmd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.invictus.xmd.R
import com.invictus.xmd.core.HistoryEntry

class HistoryAdapter(
    private val onTap: (HistoryEntry) -> Unit,
    private val onDeleteTap: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<HistoryEntry> = emptyList()

    fun submitList(list: List<HistoryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    /** Used by swipe-to-delete: which entry backs the row at [position]. */
    fun entryAt(position: Int): HistoryEntry? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.title.text = entry.title
        holder.url.text = entry.url
        holder.itemView.setOnClickListener { onTap(entry) }
        holder.deleteButton.setOnClickListener { onDeleteTap(entry) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.historyItemTitle)
        val url: TextView = view.findViewById(R.id.historyItemUrl)
        val deleteButton: ImageButton = view.findViewById(R.id.historyItemDelete)
    }
}
