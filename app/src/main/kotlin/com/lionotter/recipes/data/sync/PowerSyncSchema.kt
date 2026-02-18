package com.lionotter.recipes.data.sync

import com.powersync.ExperimentalPowerSyncAPI
import com.powersync.db.schema.PendingStatement
import com.powersync.db.schema.PendingStatementParameter
import com.powersync.db.schema.RawTable
import com.powersync.db.schema.Schema

/**
 * PowerSync schema definition matching the Room entities that should be synced.
 * Only recipes and meal_plans are synced; pending_imports and import_debug stay local.
 *
 * Uses RawTable (required for Room integration) so PowerSync doesn't create views
 * that conflict with Room's schema. The SQL statements here must match the Room
 * entity column names exactly.
 *
 * Note: owner_id is NOT included here — it exists only on the server side.
 * PowerSync sync rules filter by owner_id via the bucket parameter.
 */
@OptIn(ExperimentalPowerSyncAPI::class)
val powerSyncSchema = Schema(
    RawTable(
        name = "recipes",
        put = PendingStatement(
            sql = """
                INSERT OR REPLACE INTO recipes (
                    id, name, sourceUrl, story, servings, prepTime, cookTime, totalTime,
                    ingredientSectionsJson, instructionSectionsJson, equipmentJson, tagsJson,
                    imageUrl, sourceImageUrl, originalHtml, createdAt, updatedAt, isFavorite
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(
                PendingStatementParameter.Id,
                PendingStatementParameter.Column("name"),
                PendingStatementParameter.Column("sourceUrl"),
                PendingStatementParameter.Column("story"),
                PendingStatementParameter.Column("servings"),
                PendingStatementParameter.Column("prepTime"),
                PendingStatementParameter.Column("cookTime"),
                PendingStatementParameter.Column("totalTime"),
                PendingStatementParameter.Column("ingredientSectionsJson"),
                PendingStatementParameter.Column("instructionSectionsJson"),
                PendingStatementParameter.Column("equipmentJson"),
                PendingStatementParameter.Column("tagsJson"),
                PendingStatementParameter.Column("imageUrl"),
                PendingStatementParameter.Column("sourceImageUrl"),
                PendingStatementParameter.Column("originalHtml"),
                PendingStatementParameter.Column("createdAt"),
                PendingStatementParameter.Column("updatedAt"),
                PendingStatementParameter.Column("isFavorite"),
            ),
        ),
        delete = PendingStatement(
            sql = "DELETE FROM recipes WHERE id = ?",
            parameters = listOf(PendingStatementParameter.Id),
        ),
    ),
    RawTable(
        name = "meal_plans",
        put = PendingStatement(
            sql = """
                INSERT OR REPLACE INTO meal_plans (
                    id, recipeId, recipeName, recipeImageUrl, date, mealType,
                    servings, createdAt, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(
                PendingStatementParameter.Id,
                PendingStatementParameter.Column("recipeId"),
                PendingStatementParameter.Column("recipeName"),
                PendingStatementParameter.Column("recipeImageUrl"),
                PendingStatementParameter.Column("date"),
                PendingStatementParameter.Column("mealType"),
                PendingStatementParameter.Column("servings"),
                PendingStatementParameter.Column("createdAt"),
                PendingStatementParameter.Column("updatedAt"),
            ),
        ),
        delete = PendingStatement(
            sql = "DELETE FROM meal_plans WHERE id = ?",
            parameters = listOf(PendingStatementParameter.Id),
        ),
    ),
)
