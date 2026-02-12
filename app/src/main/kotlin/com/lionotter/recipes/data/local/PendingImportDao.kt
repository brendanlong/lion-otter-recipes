package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingImportDao {
    @Query("SELECT * FROM pending_imports ORDER BY createdAt ASC")
    fun getAllPendingImports(): Flow<List<PendingImportEntity>>

    @Query("SELECT * FROM pending_imports WHERE id = :id")
    suspend fun getPendingImportById(id: String): PendingImportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingImport(entity: PendingImportEntity)

    @Query("UPDATE pending_imports SET name = :name, imageUrl = :imageUrl, status = :status WHERE id = :id")
    suspend fun updateMetadata(id: String, name: String?, imageUrl: String?, status: String)

    @Query("UPDATE pending_imports SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE pending_imports SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatusWithError(id: String, status: String, errorMessage: String?)

    @Query("UPDATE pending_imports SET workManagerId = :workManagerId WHERE id = :id")
    suspend fun updateWorkManagerId(id: String, workManagerId: String)

    @Query("SELECT * FROM pending_imports WHERE workManagerId = :workManagerId")
    suspend fun getPendingImportByWorkManagerId(workManagerId: String): PendingImportEntity?

    @Query("DELETE FROM pending_imports WHERE id = :id")
    suspend fun deletePendingImport(id: String)

    @Query("DELETE FROM pending_imports")
    suspend fun deleteAll()
}
