package com.lionotter.recipes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PendingImportEntity::class,
        ImportDebugEntity::class
    ],
    version = 11,
    exportSchema = true
)
@TypeConverters(InstantConverter::class)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun pendingImportDao(): PendingImportDao
    abstract fun importDebugDao(): ImportDebugDao
}
