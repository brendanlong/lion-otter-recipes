package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingDeleteDao {
    @Query("SELECT * FROM pending_deletes ORDER BY deletedAt ASC")
    suspend fun getAllPendingDeletes(): List<PendingDeleteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingDelete(entity: PendingDeleteEntity)

    @Query("DELETE FROM pending_deletes WHERE recipeId = :recipeId")
    suspend fun deletePendingDelete(recipeId: String)

    @Query("DELETE FROM pending_deletes")
    suspend fun deleteAll()
}
