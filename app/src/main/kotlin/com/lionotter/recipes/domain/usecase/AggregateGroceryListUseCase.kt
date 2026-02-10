package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.util.singularize
import javax.inject.Inject

/**
 * A single ingredient contribution from one recipe (possibly appearing multiple times in the meal plan).
 */
data class GroceryIngredientSource(
    val recipeName: String,
    val ingredient: Ingredient,
    val scale: Double
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
 * - Within the same recipe, merge duplicate sources (e.g., same recipe on two different days)
 *   by summing their scale factors
 */
class AggregateGroceryListUseCase @Inject constructor() {

    companion object {
        private val SIZE_PREFIXES = setOf("small", "medium", "large")
    }

    /**
     * Aggregate ingredients from the given recipes scaled by their meal plan servings.
     *
     * @param entries The selected meal plan entries with their corresponding recipes
     * @return A list of aggregated grocery items
     */
    fun execute(entries: List<Pair<MealPlanEntry, Recipe>>): List<GroceryItem> {
        // Map of normalized ingredient key -> map of (recipeName, ingredientName) -> merged source
        val aggregation = linkedMapOf<String, LinkedHashMap<String, MutableGrocerySource>>()
        // Track the first display name seen for each key
        val displayNames = linkedMapOf<String, String>()

        for ((entry, recipe) in entries) {
            // entry.servings is a scale multiplier (1.0 = full recipe)
            val scale = entry.servings
            val sections = recipe.aggregateIngredients()
            for (section in sections) {
                for (ingredient in section.ingredients) {
                    val key = normalizeKey(ingredient.name)
                    if (key !in displayNames) {
                        displayNames[key] = normalizeName(ingredient.name)
                    }

                    val sourceMap = aggregation.getOrPut(key) { linkedMapOf() }
                    // Use recipe name + original ingredient name as source key
                    // so same recipe appearing multiple times merges into one source
                    val sourceKey = "${recipe.name}\u0000${ingredient.name}"
                    val existing = sourceMap[sourceKey]
                    if (existing != null) {
                        existing.scale += scale
                    } else {
                        sourceMap[sourceKey] = MutableGrocerySource(
                            recipeName = recipe.name,
                            ingredient = ingredient,
                            scale = scale
                        )
                    }
                }
            }
        }

        return aggregation.map { (key, sourceMap) ->
            GroceryItem(
                normalizedName = displayNames[key] ?: key,
                sources = sourceMap.values.map { it.toSource() }
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
        val words = name.lowercase().trim().split("\\s+".toRegex())
        val filtered = words.filter { it !in SIZE_PREFIXES }
        val joined = if (filtered.isNotEmpty()) filtered.joinToString(" ") else words.joinToString(" ")
        return joined.singularize()
    }

    /**
     * Normalize the display name: remove size prefixes but keep original casing.
     */
    private fun normalizeName(name: String): String {
        val words = name.trim().split("\\s+".toRegex())
        return words.filter { it.lowercase() !in SIZE_PREFIXES }.joinToString(" ").ifEmpty { name }
    }

    private class MutableGrocerySource(
        val recipeName: String,
        val ingredient: Ingredient,
        var scale: Double
    ) {
        fun toSource() = GroceryIngredientSource(
            recipeName = recipeName,
            ingredient = ingredient,
            scale = scale
        )
    }
}
