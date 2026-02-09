package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Recipe
import javax.inject.Inject

class GetTagsUseCase @Inject constructor() {
    companion object {
        private const val MAX_TAGS = 10
    }

    /**
     * Returns the top tags using a greedy set cover algorithm to maximize coverage,
     * sorted by total recipe count.
     *
     * Algorithm:
     * 1. Start with a set of all recipes
     * 2. Find the tag with the most recipes in that set
     * 3. Add that tag to results and remove its recipes from the set
     * 4. Repeat until we have 10 tags or no more tags exist
     * 5. If set becomes empty but we have fewer than 10 tags, reset the set
     * 6. Sort final tags by total recipe count (descending)
     */
    fun execute(recipes: List<Recipe>): List<String> {
        // Build tag -> recipe IDs map
        val tagToRecipes = mutableMapOf<String, MutableSet<String>>()
        for (recipe in recipes) {
            for (tag in recipe.tags) {
                tagToRecipes.getOrPut(tag) { mutableSetOf() }.add(recipe.id)
            }
        }

        if (tagToRecipes.isEmpty()) {
            return emptyList()
        }

        // If we have 10 or fewer tags, just return them sorted by count
        if (tagToRecipes.size <= MAX_TAGS) {
            return tagToRecipes.entries
                .sortedByDescending { it.value.size }
                .map { it.key }
        }

        // Greedy set cover algorithm
        val allRecipeIds = recipes.map { it.id }.toSet()
        val selectedTags = mutableListOf<String>()
        var uncoveredRecipes = allRecipeIds.toMutableSet()

        while (selectedTags.size < MAX_TAGS) {
            // Find tag that covers most uncovered recipes
            val bestTag = tagToRecipes
                .filterKeys { it !in selectedTags }
                .maxByOrNull { (_, recipeIds) ->
                    recipeIds.count { it in uncoveredRecipes }
                }
                ?.key
                ?: break // No more tags available

            selectedTags.add(bestTag)

            // Remove covered recipes
            uncoveredRecipes.removeAll(tagToRecipes[bestTag] ?: emptySet())

            // If all recipes are covered but we need more tags, reset the set
            if (uncoveredRecipes.isEmpty() && selectedTags.size < MAX_TAGS) {
                uncoveredRecipes = allRecipeIds.toMutableSet()
                // Remove recipes already covered by selected tags
                for (tag in selectedTags) {
                    uncoveredRecipes.removeAll(tagToRecipes[tag] ?: emptySet())
                }
                // If still empty (all recipes covered), reset completely
                if (uncoveredRecipes.isEmpty()) {
                    uncoveredRecipes = allRecipeIds.toMutableSet()
                }
            }
        }

        // Sort by total recipe count (descending)
        return selectedTags.sortedByDescending { tagToRecipes[it]?.size ?: 0 }
    }
}
