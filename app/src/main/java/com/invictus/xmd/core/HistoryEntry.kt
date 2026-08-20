package com.invictus.xmd.core

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One visited page in the Browser tab, most-recent first. */
@Entity(tableName = "history_entries")
data class HistoryEntry(
    @PrimaryKey
    val id: String,
    val url: String,
    val title: String,
    val visitedAtMs: Long = System.currentTimeMillis()
)
