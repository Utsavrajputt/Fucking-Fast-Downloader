package com.invictus.xmd.core.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.invictus.xmd.core.Bookmark

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY sortOrder ASC, createdAtMs ASC")
    fun observeAll(): LiveData<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY sortOrder ASC, createdAtMs ASC")
    suspend fun getAll(): List<Bookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: Bookmark)

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)
}
