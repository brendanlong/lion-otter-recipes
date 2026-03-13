package com.lionotter.recipes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PendingImportEntity::class,
        ImportDebugEntity::class,
        PendingMigrationEntity::class
    ],
    version = 13,
    exportSchema = true
)
@TypeConverters(InstantConverter::class)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun pendingImportDao(): PendingImportDao
    abstract fun importDebugDao(): ImportDebugDao
    abstract fun pendingMigrationDao(): PendingMigrationDao
}
