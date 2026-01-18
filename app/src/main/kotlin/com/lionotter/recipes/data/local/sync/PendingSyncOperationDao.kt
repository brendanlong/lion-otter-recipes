package com.lionotter.recipes.data.local.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSyncOperationDao {

    @Query("SELECT * FROM pending_sync_operations WHERE id = :id")
    suspend fun getById(id: Long): PendingSyncOperationEntity?

    @Query("SELECT * FROM pending_sync_operations WHERE status IN ('PENDING', 'FAILED_RETRYING') ORDER BY createdAt ASC")
    suspend fun getPendingOperations(): List<PendingSyncOperationEntity>

    @Query("SELECT * FROM pending_sync_operations WHERE status IN ('PENDING', 'FAILED_RETRYING') ORDER BY createdAt ASC")
    fun getPendingOperationsFlow(): Flow<List<PendingSyncOperationEntity>>

    @Query("SELECT * FROM pending_sync_operations WHERE operationType = :type AND status IN ('PENDING', 'FAILED_RETRYING')")
    suspend fun getPendingByType(type: SyncOperationType): List<PendingSyncOperationEntity>

    @Query("SELECT * FROM pending_sync_operations WHERE localRecipeId = :recipeId AND status IN ('PENDING', 'IN_PROGRESS', 'FAILED_RETRYING')")
    suspend fun getPendingForRecipe(recipeId: String): List<PendingSyncOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_sync_operations WHERE status IN ('PENDING', 'FAILED_RETRYING')")
    fun getPendingCountFlow(): Flow<Int>

    @Insert
    suspend fun insert(operation: PendingSyncOperationEntity): Long

    @Update
    suspend fun update(operation: PendingSyncOperationEntity)

    @Query("UPDATE pending_sync_operations SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: OperationStatus)

    @Query("""
        UPDATE pending_sync_operations
        SET status = :status, lastAttemptAt = :attemptAt, attemptCount = attemptCount + 1, lastError = :error
        WHERE id = :id
    """)
    suspend fun recordAttempt(id: Long, status: OperationStatus, attemptAt: Long, error: String?)

    @Query("DELETE FROM pending_sync_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_sync_operations WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM pending_sync_operations WHERE status = 'ABANDONED'")
    suspend fun deleteAbandoned()

    /**
     * Abandon pending upload operations for a recipe that no longer exists.
     */
    @Query("""
        UPDATE pending_sync_operations
        SET status = 'ABANDONED'
        WHERE localRecipeId = :recipeId AND operationType = 'UPLOAD' AND status IN ('PENDING', 'FAILED_RETRYING')
    """)
    suspend fun abandonUploadsForRecipe(recipeId: String)

    /**
     * Check if there's a pending delete for a specific Drive folder.
     */
    @Query("""
        SELECT * FROM pending_sync_operations
        WHERE driveFolderId = :driveFolderId AND operationType = 'DELETE' AND status IN ('PENDING', 'IN_PROGRESS', 'FAILED_RETRYING')
        LIMIT 1
    """)
    suspend fun getPendingDeleteForFolder(driveFolderId: String): PendingSyncOperationEntity?
}
