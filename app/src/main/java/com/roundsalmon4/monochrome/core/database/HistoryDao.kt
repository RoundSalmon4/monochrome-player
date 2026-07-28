package com.roundsalmon4.monochrome.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roundsalmon4.monochrome.core.database.entity.ListenHistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM listen_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ListenHistoryEntry>>

    @Query("SELECT * FROM listen_history WHERE trackId = :trackId")
    suspend fun getById(trackId: String): ListenHistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ListenHistoryEntry)

    @Query("DELETE FROM listen_history WHERE trackId = :trackId")
    suspend fun delete(trackId: String)

    @Query("DELETE FROM listen_history")
    suspend fun clearAll()
}
