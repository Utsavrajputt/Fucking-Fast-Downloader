package com.invictus.xmd.core

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-saved speed-dial tile shown on the Browser tab's new-tab page,
 * Chrome-style. [faviconUrl] is resolved and cached by BrowserFragment on
 * first visit (host's /favicon.ico) -- not fetched here.
 */
@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey
    val id: String,
    val title: String,
    val url: String,
    val faviconUrl: String? = null,
    val sortOrder: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)
