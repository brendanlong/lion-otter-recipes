package com.lionotter.recipes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecipeEntity::class, ImportDebugEntity::class, PendingImportEntity::class, MealPlanEntity::class],
    version = 11,
    exportSchema = true
)
@TypeConverters(InstantConverter::class)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun importDebugDao(): ImportDebugDao
    abstract fun pendingImportDao(): PendingImportDao
    abstract fun mealPlanDao(): MealPlanDao

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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS meal_plans (
                        id TEXT NOT NULL PRIMARY KEY,
                        recipeId TEXT NOT NULL,
                        recipeName TEXT NOT NULL,
                        recipeImageUrl TEXT,
                        date TEXT NOT NULL,
                        mealType TEXT NOT NULL,
                        servings REAL NOT NULL DEFAULT 1.0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN equipmentJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN sourceImageUrl TEXT")
            }
        }

        /**
         * Remove soft-delete columns from recipes and meal_plans.
         * First purges any soft-deleted rows, then recreates the tables without
         * the deleted column.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN userNotes TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Purge soft-deleted rows before dropping the column
                db.execSQL("DELETE FROM recipes WHERE deleted = 1")
                db.execSQL("DELETE FROM meal_plans WHERE deleted = 1")

                // Recreate recipes table without deleted column
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recipes_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sourceUrl TEXT,
                        story TEXT,
                        servings INTEGER,
                        prepTime TEXT,
                        cookTime TEXT,
                        totalTime TEXT,
                        ingredientSectionsJson TEXT NOT NULL,
                        instructionSectionsJson TEXT NOT NULL,
                        equipmentJson TEXT NOT NULL DEFAULT '[]',
                        tagsJson TEXT NOT NULL,
                        imageUrl TEXT,
                        sourceImageUrl TEXT,
                        originalHtml TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO recipes_new (id, name, sourceUrl, story, servings, prepTime, cookTime, totalTime,
                        ingredientSectionsJson, instructionSectionsJson, equipmentJson, tagsJson, imageUrl,
                        sourceImageUrl, originalHtml, createdAt, updatedAt, isFavorite)
                    SELECT id, name, sourceUrl, story, servings, prepTime, cookTime, totalTime,
                        ingredientSectionsJson, instructionSectionsJson, equipmentJson, tagsJson, imageUrl,
                        sourceImageUrl, originalHtml, createdAt, updatedAt, isFavorite
                    FROM recipes
                """.trimIndent())
                db.execSQL("DROP TABLE recipes")
                db.execSQL("ALTER TABLE recipes_new RENAME TO recipes")

                // Recreate meal_plans table without deleted column
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS meal_plans_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        recipeId TEXT NOT NULL,
                        recipeName TEXT NOT NULL,
                        recipeImageUrl TEXT,
                        date TEXT NOT NULL,
                        mealType TEXT NOT NULL,
                        servings REAL NOT NULL DEFAULT 1.0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO meal_plans_new (id, recipeId, recipeName, recipeImageUrl, date, mealType,
                        servings, createdAt, updatedAt)
                    SELECT id, recipeId, recipeName, recipeImageUrl, date, mealType,
                        servings, createdAt, updatedAt
                    FROM meal_plans
                """.trimIndent())
                db.execSQL("DROP TABLE meal_plans")
                db.execSQL("ALTER TABLE meal_plans_new RENAME TO meal_plans")
            }
        }
    }
}
