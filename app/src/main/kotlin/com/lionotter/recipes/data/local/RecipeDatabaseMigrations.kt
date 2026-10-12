package com.lionotter.recipes.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room migrations for [RecipeDatabase]; covered by RecipeDatabaseMigrationTest. */
object RecipeDatabaseMigrations {
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS recipes")
            db.execSQL("DROP TABLE IF EXISTS meal_plans")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE import_debug ADD COLUMN batchMode INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_10_11, MIGRATION_11_12)

    /**
     * Pre-10 schemas predate the Firestore move; the remaining tables only hold the import
     * queue and debug data, so recreating them is safe.
     */
    val DESTRUCTIVE_FROM: IntArray = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
}
