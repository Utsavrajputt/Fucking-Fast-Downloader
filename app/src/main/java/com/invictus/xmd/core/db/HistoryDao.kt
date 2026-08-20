package com.invictus.xmd.core.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.invictus.xmd.core.HistoryEntry

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_entries ORDER BY visitedAtMs DESC LIMIT 500")
    fun observeAll(): LiveData<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Delete
    suspend fun delete(entry: HistoryEntry)

    @Query("DELETE FROM history_entries")
    suspend fun clearAll()
}
