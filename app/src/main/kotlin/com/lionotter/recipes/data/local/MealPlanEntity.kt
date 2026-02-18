package com.lionotter.recipes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

@Entity(tableName = "meal_plans")
data class MealPlanEntity(
    @PrimaryKey
    val id: String,
    val recipeId: String,
    val recipeName: String,
    val recipeImageUrl: String?,
    val date: String, // ISO-8601 date string (yyyy-MM-dd)
    val mealType: String, // MealType enum name
    val servings: Double = 1.0,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun toMealPlanEntry(): MealPlanEntry {
        return MealPlanEntry(
            id = id,
            recipeId = recipeId,
            recipeName = recipeName,
            recipeImageUrl = recipeImageUrl,
            date = LocalDate.parse(date),
            mealType = try {
                MealType.valueOf(mealType)
            } catch (_: IllegalArgumentException) {
                MealType.DINNER
            },
            servings = servings,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    companion object {
        fun fromMealPlanEntry(entry: MealPlanEntry): MealPlanEntity {
            return MealPlanEntity(
                id = entry.id,
                recipeId = entry.recipeId,
                recipeName = entry.recipeName,
                recipeImageUrl = entry.recipeImageUrl,
                date = entry.date.toString(),
                mealType = entry.mealType.name,
                servings = entry.servings,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt
            )
        }
    }
}
