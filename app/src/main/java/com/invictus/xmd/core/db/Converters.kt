package com.invictus.xmd.core.db

import androidx.room.TypeConverter
import com.invictus.xmd.core.DownloadCategory
import com.invictus.xmd.core.ItemStatus
import com.invictus.xmd.core.MediaPlatform

/**
 * Room can't store enums natively -- it needs an explicit mapping to a
 * column type. We store both as their enum name (String) rather than
 * ordinal so that reordering/inserting entries in ItemStatus or
 * DownloadCategory later doesn't silently corrupt already-persisted rows.
 */
class Converters {
    @TypeConverter
    fun fromItemStatus(value: ItemStatus): String = value.name

    @TypeConverter
    fun toItemStatus(value: String): ItemStatus =
        runCatching { ItemStatus.valueOf(value) }.getOrDefault(ItemStatus.FAILED)

    @TypeConverter
    fun fromDownloadCategory(value: DownloadCategory): String = value.name

    @TypeConverter
    fun toDownloadCategory(value: String): DownloadCategory =
        runCatching { DownloadCategory.valueOf(value) }.getOrDefault(DownloadCategory.default())

    @TypeConverter
    fun fromMediaPlatform(value: MediaPlatform): String = value.name

    @TypeConverter
    fun toMediaPlatform(value: String): MediaPlatform =
        runCatching { MediaPlatform.valueOf(value) }.getOrDefault(MediaPlatform.DIRECT)
}
