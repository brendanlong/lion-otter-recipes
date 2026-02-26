package com.lionotter.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingMigrationDao {
    @Query("SELECT * FROM pending_migration WHERE type = :type")
    suspend fun getAllByType(type: String): List<PendingMigrationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PendingMigrationEntity>)

    @Query("DELETE FROM pending_migration")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM pending_migration")
    suspend fun count(): Int
}
