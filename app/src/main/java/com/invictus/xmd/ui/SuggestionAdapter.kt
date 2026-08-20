package com.invictus.xmd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.invictus.xmd.R

/**
 * Row = a phrase from SuggestApi (DuckDuckGo). Tapping the row loads it
 * (as a search or URL, same normalization as manual address-bar entry);
 * tapping the trailing "+" saves it as a bookmark without navigating.
 */
class SuggestionAdapter(
    private val onTap: (String) -> Unit,
    private val onAddTap: (String) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {

    private var suggestions: List<String> = emptyList()

    fun submitList(items: List<String>) {
        suggestions = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val phrase = suggestions[position]
        holder.text.text = phrase
        holder.itemView.setOnClickListener { onTap(phrase) }
        holder.addButton.setOnClickListener { onAddTap(phrase) }
    }

    override fun getItemCount(): Int = suggestions.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.suggestionText)
        val addButton: ImageButton = view.findViewById(R.id.suggestionAddButton)
    }
}
