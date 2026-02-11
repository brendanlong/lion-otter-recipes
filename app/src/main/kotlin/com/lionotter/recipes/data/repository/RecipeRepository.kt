package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(
    private val firestoreService: FirestoreService
) {
    fun getAllRecipes(): Flow<List<Recipe>> {
        return firestoreService.observeRecipes().map { remoteRecipes ->
            remoteRecipes
                .map { it.recipe }
                .sortedByDescending { it.updatedAt }
        }
    }

    fun getRecipeById(id: String): Flow<Recipe?> {
        return firestoreService.observeRecipeById(id).map { it?.recipe }
    }

    suspend fun getRecipeByIdOnce(id: String): Recipe? {
        return firestoreService.getRecipeById(id)?.recipe
    }

    suspend fun getOriginalHtml(id: String): String? {
        return firestoreService.getRecipeById(id)?.originalHtml
    }

    suspend fun saveRecipe(recipe: Recipe, originalHtml: String? = null) {
        firestoreService.upsertRecipe(recipe, originalHtml).getOrThrow()
    }

    suspend fun deleteRecipe(id: String) {
        firestoreService.deleteRecipe(id).getOrThrow()
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) {
        val remoteRecipe = firestoreService.getRecipeById(id) ?: return
        val updatedRecipe = remoteRecipe.recipe.copy(
            isFavorite = isFavorite,
            updatedAt = Clock.System.now()
        )
        firestoreService.upsertRecipe(updatedRecipe, remoteRecipe.originalHtml).getOrThrow()
    }

    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> {
        val result = firestoreService.getAllRecipes()
        return result.getOrDefault(emptyList()).map { remoteRecipe ->
            RecipeIdAndName(id = remoteRecipe.recipe.id, name = remoteRecipe.recipe.name)
        }
    }

    fun searchRecipes(query: String): Flow<List<Recipe>> {
        return firestoreService.observeRecipes().map { remoteRecipes ->
            remoteRecipes
                .map { it.recipe }
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedByDescending { it.updatedAt }
        }
    }
}
