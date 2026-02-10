package com.lionotter.recipes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecipeEntity::class, ImportDebugEntity::class, PendingImportEntity::class],
    version = 5,
    exportSchema = true
)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun importDebugDao(): ImportDebugDao
    abstract fun pendingImportDao(): PendingImportDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS import_debug (
                        id TEXT NOT NULL PRIMARY KEY,
                        sourceUrl TEXT,
                        originalHtml TEXT,
                        cleanedContent TEXT,
                        aiOutputJson TEXT,
                        originalLength INTEGER NOT NULL,
                        cleanedLength INTEGER NOT NULL,
                        inputTokens INTEGER,
                        outputTokens INTEGER,
                        aiModel TEXT,
                        thinkingEnabled INTEGER NOT NULL,
                        recipeId TEXT,
                        recipeName TEXT,
                        errorMessage TEXT,
                        isError INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_debug ADD COLUMN durationMs INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_imports (
                        id TEXT NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL,
                        name TEXT,
                        imageUrl TEXT,
                        status TEXT NOT NULL,
                        workManagerId TEXT,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}
