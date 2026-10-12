package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.util.singularize
import javax.inject.Inject

/**
 * A single ingredient contribution from one recipe (possibly appearing multiple times in the meal plan).
 *
 * [ingredient] is the recipe's total for this ingredient across all of its sections, and [scale]
 * is the sum of the meal plan servings multipliers for that recipe.
 * [key] uniquely identifies this source within its [GroceryItem]; use it for check-off state
 * (recipe names are not unique, and one recipe can contribute two sources for the same
 * ingredient when its amounts can't be combined, e.g. "1 cup" and "50 g" with no density).
 */
data class GroceryIngredientSource(
    val recipeName: String,
    val ingredient: Ingredient,
    val scale: Double,
    val recipeId: String = recipeName,
    val key: String = "$recipeId\u0000${ingredient.name.lowercase()}\u0000${ingredient.amount?.unit.orEmpty()}"
)

/**
 * An aggregated grocery item that combines the same ingredient across multiple recipes.
 */
data class GroceryItem(
    val normalizedName: String,
    val sources: List<GroceryIngredientSource>
)

/**
 * Aggregates ingredients from selected meal plan recipes into a deduplicated grocery list.
 *
 * Deduplication strategy:
 * - Normalize ingredient names by removing size prefixes (small, medium, large),
 *   lowercasing, and singularizing
 * - Group same ingredients across recipes
 * - Within one recipe, combine the same ingredient across sections by summing amounts
 *   (e.g. butter in both the cake and the frosting)
 * - The same recipe planned several times (matched by recipe ID) becomes one source whose
 *   scale is the sum of the planned servings multipliers
 */
class AggregateGroceryListUseCase @Inject constructor() {

    companion object {
        private val SIZE_PREFIXES = setOf("small", "medium", "large")
        private val WHITESPACE = "\\s+".toRegex()
    }

    /**
     * Aggregate ingredients from the given recipes scaled by their meal plan servings.
     *
     * @param entries The selected meal plan entries with their corresponding recipes
     * @return A list of aggregated grocery items
     */
    fun execute(entries: List<Pair<MealPlanEntry, Recipe>>): List<GroceryItem> {
        // entry.servings is a scale multiplier (1.0 = full recipe)
        val recipesById = linkedMapOf<String, Pair<Recipe, Double>>()
        for ((entry, recipe) in entries) {
            val existingScale = recipesById[recipe.id]?.second ?: 0.0
            recipesById[recipe.id] = recipe to (existingScale + entry.servings)
        }

        val aggregation = linkedMapOf<String, MutableList<GroceryIngredientSource>>()
        // Track the first display name seen for each key
        val displayNames = linkedMapOf<String, String>()

        for ((recipe, scale) in recipesById.values) {
            for (ingredient in recipe.aggregateAllIngredients()) {
                val key = normalizeKey(ingredient.name)
                if (key !in displayNames) {
                    displayNames[key] = normalizeName(ingredient.name)
                }
                aggregation.getOrPut(key) { mutableListOf() }.add(
                    GroceryIngredientSource(
                        recipeName = recipe.name,
                        ingredient = ingredient,
                        scale = scale,
                        recipeId = recipe.id
                    )
                )
            }
        }

        return aggregation.map { (key, sources) ->
            GroceryItem(
                normalizedName = displayNames[key] ?: key,
                sources = sources
            )
        }
    }

    /**
     * Normalize an ingredient name for deduplication:
     * - Lowercase
     * - Remove size prefixes (small, medium, large)
     * - Singularize
     */
    private fun normalizeKey(name: String): String {
        val words = name.lowercase().trim().split(WHITESPACE)
        val filtered = words.filter { it !in SIZE_PREFIXES }
        val joined = if (filtered.isNotEmpty()) filtered.joinToString(" ") else words.joinToString(" ")
        return joined.singularize()
    }

    /**
     * Normalize the display name: remove size prefixes but keep original casing.
     */
    private fun normalizeName(name: String): String {
        val words = name.trim().split(WHITESPACE)
        return words.filter { it.lowercase() !in SIZE_PREFIXES }.joinToString(" ").ifEmpty { name }
    }
}
