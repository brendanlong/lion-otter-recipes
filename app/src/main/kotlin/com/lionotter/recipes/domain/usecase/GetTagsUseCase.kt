package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.tagKey
import org.ojalgo.optimisation.ExpressionsBasedModel
import org.ojalgo.optimisation.Optimisation
import javax.inject.Inject

class GetTagsUseCase @Inject constructor() {
    companion object {
        private const val TAG = "GetTagsUseCase"
        internal const val MAX_TAGS = 10
        internal const val GAMMA_MULTIPLIER = 10.0 // γ = GAMMA_MULTIPLIER * maxTagSize

        // The ILP grows with recipes × tags; beyond these sizes use the greedy solver so the
        // worst case stays bounded on low-end phones.
        internal const val ILP_MAX_RECIPES = 300
        internal const val ILP_MAX_TAGS = 200
        private const val ILP_TIME_ABORT_MS = 2_000L
        private const val ILP_TIME_SUFFICE_MS = 500L
    }

    /**
     * Returns the top tags using an ILP (Integer Linear Programming) optimization to maximize
     * recipe coverage while preferring specific tags over generic ones.
     *
     * Tags are grouped case-insensitively ("Dessert" and "dessert" count as one tag); the
     * returned name is the most common spelling.
     *
     * Falls back to a greedy set cover algorithm if the ILP solver fails, times out, or the
     * problem is too large to solve quickly.
     *
     * ILP formulation:
     *   Maximize: Σ_j (x_j · w_j) - γ · Σ_i u_i
     *   Subject to:
     *     Σ_j (x_j · a_ij) + u_i ≥ 1  for all recipes i (coverage with slack)
     *     Σ_j x_j = k                   (select exactly k tags)
     *     x_j ∈ {0,1}, u_i ∈ {0,1}
     *
     * Where:
     *   w_j = |tag_j| · ln(N / |tag_j|)  — IDF-weighted coverage score
     *   γ = GAMMA_MULTIPLIER * maxTagSize — uncovered recipe penalty
     *
     * The IDF-like weighting naturally balances coverage and specificity:
     * - A tag covering all N recipes scores N·ln(1) = 0 (no filtering value)
     * - A tag covering 1 recipe scores 1·ln(N) (maximum specificity)
     * - The peak is at N/e ≈ 37% of recipes
     */
    fun execute(recipes: List<Recipe>): List<String> {
        val tagToRecipes = buildTagMap(recipes)

        if (tagToRecipes.isEmpty()) {
            return emptyList()
        }

        if (tagToRecipes.size <= MAX_TAGS) {
            return tagToRecipes.entries
                .sortedByDescending { it.value.size }
                .map { it.key }
        }

        val recipeCount = recipes.distinctBy { it.id }.size
        if (recipeCount > ILP_MAX_RECIPES || tagToRecipes.size > ILP_MAX_TAGS) {
            return solveGreedy(recipes, tagToRecipes)
        }

        return try {
            solveIlp(recipes, tagToRecipes)
        } catch (e: Exception) {
            Log.w(TAG, "ILP solver failed, falling back to greedy algorithm", e)
            solveGreedy(recipes, tagToRecipes)
        }
    }

    /**
     * Map each tag (by its display name) to the IDs of recipes carrying it. Tags are grouped
     * case-insensitively and whitespace-trimmed; the display name is the most frequently used
     * spelling, preferring a capitalized one on ties.
     */
    internal fun buildTagMap(recipes: List<Recipe>): Map<String, Set<String>> {
        val recipesByKey = linkedMapOf<String, MutableSet<String>>()
        val spellingsByKey = mutableMapOf<String, MutableMap<String, Int>>()
        for (recipe in recipes) {
            for (rawTag in recipe.tags) {
                val tag = rawTag.trim()
                if (tag.isEmpty()) continue
                val key = tagKey(tag)
                recipesByKey.getOrPut(key) { mutableSetOf() }.add(recipe.id)
                val spellings = spellingsByKey.getOrPut(key) { mutableMapOf() }
                spellings[tag] = (spellings[tag] ?: 0) + 1
            }
        }
        return recipesByKey.entries.associate { (key, recipeIds) ->
            val displayName = spellingsByKey.getValue(key).entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { it.key.first().isUpperCase() }
                        .thenBy { it.key }
                )
                .first().key
            displayName to recipeIds
        }
    }

    internal fun solveIlp(
        recipes: List<Recipe>,
        tagToRecipes: Map<String, Set<String>>
    ): List<String> {
        val tags = tagToRecipes.keys.toList()
        val recipeIds = recipes.map { it.id }.distinct()
        val recipeCount = recipeIds.size
        val maxTagSize = tagToRecipes.values.maxOf { it.size }

        // Build recipe-to-index lookup
        val recipeIndex = recipeIds.withIndex().associate { (idx, id) -> id to idx }

        // Build tag membership matrix: a_ij = 1 if recipe i has tag j
        val membership = Array(recipeCount) { BooleanArray(tags.size) }
        for ((j, tag) in tags.withIndex()) {
            for (recipeId in tagToRecipes.getValue(tag)) {
                val i = recipeIndex[recipeId] ?: continue
                membership[i][j] = true
            }
        }

        val gamma = GAMMA_MULTIPLIER * maxTagSize

        val model = ExpressionsBasedModel()
        model.options.time_abort = ILP_TIME_ABORT_MS
        model.options.time_suffice = ILP_TIME_SUFFICE_MS

        // x_j: binary variable for whether tag j is selected
        val xVars = tags.mapIndexed { j, tag ->
            model.addVariable("x_$j").binary().apply {
                // IDF-weighted coverage: |tag_j| * ln(N / |tag_j|)
                // Tags covering all recipes score 0; tags covering ~37% of recipes score highest.
                val tagSize = tagToRecipes.getValue(tag).size.toDouble()
                weight(tagSize * Math.log(recipeCount.toDouble() / tagSize))
            }
        }

        // u_i: binary slack variable for uncovered recipe i
        val uVars = recipeIds.mapIndexed { i, _ ->
            model.addVariable("u_$i").binary().apply {
                weight(-gamma) // penalty for leaving recipe uncovered
            }
        }

        // Constraint: Σ_j x_j = k (select exactly MAX_TAGS tags)
        val tagCountExpr = model.addExpression("tag_count").level(MAX_TAGS.toLong())
        for (j in tags.indices) {
            tagCountExpr.set(xVars[j], 1)
        }

        // Constraint: Σ_j (x_j · a_ij) + u_i ≥ 1 for each recipe i
        for (i in recipeIds.indices) {
            val coverageExpr = model.addExpression("coverage_$i").lower(1)
            for (j in tags.indices) {
                if (membership[i][j]) {
                    coverageExpr.set(xVars[j], 1)
                }
            }
            coverageExpr.set(uVars[i], 1)
        }

        val result = model.maximise()

        if (!result.state.isFeasible) {
            throw IllegalStateException("ILP solver returned state: ${result.state}")
        }

        // Extract selected tags
        val selectedTags = tags.indices
            .filter { j -> result.doubleValue(j) > 0.5 }
            .map { j -> tags[j] }

        // Sort by total recipe count (descending)
        return selectedTags.sortedByDescending { tagToRecipes[it]?.size ?: 0 }
    }

    internal fun solveGreedy(
        recipes: List<Recipe>,
        tagToRecipes: Map<String, Set<String>>
    ): List<String> {
        val allRecipeIds = recipes.map { it.id }.toSet()
        val selectedTags = mutableListOf<String>()
        var uncoveredRecipes = allRecipeIds.toMutableSet()

        while (selectedTags.size < MAX_TAGS) {
            val bestTag = tagToRecipes
                .filterKeys { it !in selectedTags }
                .maxByOrNull { (_, recipeIds) ->
                    recipeIds.count { it in uncoveredRecipes }
                }
                ?.key
                ?: break

            selectedTags.add(bestTag)
            uncoveredRecipes.removeAll(tagToRecipes[bestTag] ?: emptySet())

            if (uncoveredRecipes.isEmpty() && selectedTags.size < MAX_TAGS) {
                uncoveredRecipes = allRecipeIds.toMutableSet()
                for (tag in selectedTags) {
                    uncoveredRecipes.removeAll(tagToRecipes[tag] ?: emptySet())
                }
                if (uncoveredRecipes.isEmpty()) {
                    uncoveredRecipes = allRecipeIds.toMutableSet()
                }
            }
        }

        return selectedTags.sortedByDescending { tagToRecipes[it]?.size ?: 0 }
    }
}
