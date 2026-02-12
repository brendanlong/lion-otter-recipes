package com.lionotter.recipes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for local-only data: import debugging and pending imports.
 * Recipe and meal plan data is stored in Firestore (see FirestoreService).
 */
@Database(
    entities = [
        ImportDebugEntity::class,
        PendingImportEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun importDebugDao(): ImportDebugDao
    abstract fun pendingImportDao(): PendingImportDao
}

/**
 * Migration from v9 (Room-based recipes/meal plans) to v10 (Firestore-first).
 * Drops the recipes and meal_plans tables since they are now stored in Firestore.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS recipes")
        db.execSQL("DROP TABLE IF EXISTS meal_plans")
    }
}
