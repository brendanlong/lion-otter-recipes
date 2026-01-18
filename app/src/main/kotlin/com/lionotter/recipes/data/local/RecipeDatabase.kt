package com.lionotter.recipes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lionotter.recipes.data.local.sync.PendingSyncOperationDao
import com.lionotter.recipes.data.local.sync.PendingSyncOperationEntity
import com.lionotter.recipes.data.local.sync.SyncConverters
import com.lionotter.recipes.data.local.sync.SyncStateDao
import com.lionotter.recipes.data.local.sync.SyncStateEntity
import com.lionotter.recipes.data.local.sync.SyncedRecipeDao
import com.lionotter.recipes.data.local.sync.SyncedRecipeEntity

@Database(
    entities = [
        RecipeEntity::class,
        SyncedRecipeEntity::class,
        SyncStateEntity::class,
        PendingSyncOperationEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(SyncConverters::class)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun syncedRecipeDao(): SyncedRecipeDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun pendingSyncOperationDao(): PendingSyncOperationDao
}
