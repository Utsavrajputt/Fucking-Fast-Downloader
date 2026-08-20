package com.invictus.xmd.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.invictus.xmd.core.QueueItem

@Dao
interface QueueItemDao {

    @Query("SELECT * FROM queue_items")
    suspend fun getAll(): List<QueueItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: QueueItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<QueueItem>)

    @Query("DELETE FROM queue_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Delete
    suspend fun delete(item: QueueItem)
}
