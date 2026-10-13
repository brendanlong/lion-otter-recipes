package com.lionotter.recipes.domain.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Represents the type of meal in a meal plan.
 * Ordered by typical meal sequence: Breakfast > Lunch > Snack > Dinner.
 */
@Serializable
enum class MealType(val displayOrder: Int) {
    BREAKFAST(0),
    LUNCH(1),
    SNACK(2),
    DINNER(3)
}

/**
 * A single entry in the meal plan: a recipe assigned to a date and meal type.
 *
 * @property recipeImageUrl a copy of the recipe's image URL, kept current by
 *   `RecipeRepository.setImageUrl` and the local image upload pass
 * @property servings a scale multiplier for the recipe (1.0 = as written), not a serving count
 *   like `Recipe.servings`
 */
@Serializable
data class MealPlanEntry(
    val id: String,
    val recipeId: String,
    val recipeName: String,
    val recipeImageUrl: String?,
    val date: LocalDate,
    val mealType: MealType,
    val servings: Double = 1.0,
    val createdAt: Instant,
    val updatedAt: Instant
)
