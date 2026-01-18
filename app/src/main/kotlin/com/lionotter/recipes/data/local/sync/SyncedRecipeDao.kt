package com.lionotter.recipes.data.local.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedRecipeDao {

    @Query("SELECT * FROM synced_recipes WHERE localRecipeId = :recipeId")
    suspend fun getByRecipeId(recipeId: String): SyncedRecipeEntity?

    @Query("SELECT * FROM synced_recipes WHERE localRecipeId = :recipeId")
    fun getByRecipeIdFlow(recipeId: String): Flow<SyncedRecipeEntity?>

    @Query("SELECT * FROM synced_recipes WHERE driveFolderId = :driveFolderId")
    suspend fun getByDriveFolderId(driveFolderId: String): SyncedRecipeEntity?

    @Query("SELECT * FROM synced_recipes WHERE driveJsonFileId = :driveFileId")
    suspend fun getByDriveFileId(driveFileId: String): SyncedRecipeEntity?

    @Query("SELECT * FROM synced_recipes")
    suspend fun getAll(): List<SyncedRecipeEntity>

    @Query("SELECT * FROM synced_recipes")
    fun getAllFlow(): Flow<List<SyncedRecipeEntity>>

    @Query("SELECT * FROM synced_recipes WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<SyncedRecipeEntity>

    @Query("SELECT * FROM synced_recipes WHERE syncStatus IN (:statuses)")
    suspend fun getByStatuses(statuses: List<SyncStatus>): List<SyncedRecipeEntity>

    @Query("SELECT COUNT(*) FROM synced_recipes WHERE syncStatus = :status")
    fun countByStatusFlow(status: SyncStatus): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(syncedRecipe: SyncedRecipeEntity)

    @Update
    suspend fun update(syncedRecipe: SyncedRecipeEntity)

    @Query("UPDATE synced_recipes SET syncStatus = :status WHERE localRecipeId = :recipeId")
    suspend fun updateStatus(recipeId: String, status: SyncStatus)

    @Query("DELETE FROM synced_recipes WHERE localRecipeId = :recipeId")
    suspend fun deleteByRecipeId(recipeId: String)

    @Query("DELETE FROM synced_recipes WHERE driveFolderId = :driveFolderId")
    suspend fun deleteByDriveFolderId(driveFolderId: String)

    /**
     * Get all Drive folder IDs that we've synced.
     * Used to identify which Drive folders we own when processing changes.
     */
    @Query("SELECT driveFolderId FROM synced_recipes")
    suspend fun getAllDriveFolderIds(): List<String>

    /**
     * Get all Drive file IDs that we've synced.
     * Used to match incoming changes to our synced recipes.
     */
    @Query("SELECT driveJsonFileId FROM synced_recipes")
    suspend fun getAllDriveFileIds(): List<String>
}
