package com.invictus.xmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.invictus.xmd.R
import com.invictus.xmd.core.HistoryEntry
import com.invictus.xmd.core.HistoryRepository

/** Browser tab visited-page history: list, swipe-to-delete, Clear all, tap to reopen. */
class HistoryFragment : Fragment() {

    interface Callbacks {
        /** Reopens the given URL in the Browser tab. */
        fun openInBrowser(url: String)
    }

    private lateinit var backButton: ImageButton
    private lateinit var clearAllLabel: TextView
    private lateinit var list: RecyclerView
    private lateinit var emptyLabel: TextView
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backButton = view.findViewById(R.id.historyBackButton)
        clearAllLabel = view.findViewById(R.id.historyClearAll)
        list = view.findViewById(R.id.historyList)
        emptyLabel = view.findViewById(R.id.historyEmptyLabel)

        adapter = HistoryAdapter(
            onTap = { entry ->
                (activity as? Callbacks)?.openInBrowser(entry.url)
                parentFragmentManager.popBackStack()
            },
            onDeleteTap = { entry -> HistoryRepository.remove(entry) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        attachSwipeToDelete()

        backButton.setOnClickListener { parentFragmentManager.popBackStack() }
        clearAllLabel.setOnClickListener { confirmClearAll() }

        HistoryRepository.entries.observe(viewLifecycleOwner) { entries ->
            adapter.submitList(entries)
            emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val entry: HistoryEntry = adapter.entryAt(viewHolder.bindingAdapterPosition) ?: return
                HistoryRepository.remove(entry)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(list)
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_clear_all)
            .setPositiveButton(R.string.history_clear_all) { _, _ ->
                HistoryRepository.clearAll()
                android.widget.Toast.makeText(requireContext(), R.string.history_cleared_toast, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
