package com.lionotter.recipes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ImportDebugEntity::class, PendingImportEntity::class],
    version = 1,
    exportSchema = true
)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun importDebugDao(): ImportDebugDao
    abstract fun pendingImportDao(): PendingImportDao
}
