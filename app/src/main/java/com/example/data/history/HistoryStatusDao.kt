package com.example.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryStatusDao {

    @Query("SELECT * FROM history_statuses WHERE expiresAtMillis > :nowMillis ORDER BY statusModifiedMillis DESC, savedAtMillis DESC")
    fun observeActiveHistory(nowMillis: Long): Flow<List<HistoryStatusEntity>>

    @Query("SELECT * FROM history_statuses ORDER BY statusModifiedMillis DESC, savedAtMillis DESC")
    fun observeAllHistory(): Flow<List<HistoryStatusEntity>>

    @Query("SELECT id FROM history_statuses")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM history_statuses WHERE expiresAtMillis <= :nowMillis")
    suspend fun getExpiredEntries(nowMillis: Long): List<HistoryStatusEntity>

    @Query("SELECT * FROM history_statuses")
    suspend fun getAllEntries(): List<HistoryStatusEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: HistoryStatusEntity)

    @Query("DELETE FROM history_statuses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM history_statuses WHERE expiresAtMillis <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long)

    @Query("DELETE FROM history_statuses")
    suspend fun deleteAll()
}
