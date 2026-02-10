package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportDebugDao {
    @Query("SELECT * FROM import_debug ORDER BY createdAt DESC")
    fun getAllDebugEntries(): Flow<List<ImportDebugEntity>>

    @Query("SELECT * FROM import_debug WHERE id = :id")
    suspend fun getDebugEntryById(id: String): ImportDebugEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebugEntry(entry: ImportDebugEntity)

    @Query("DELETE FROM import_debug")
    suspend fun deleteAllDebugEntries()
}
