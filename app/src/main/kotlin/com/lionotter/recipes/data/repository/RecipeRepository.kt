package com.lionotter.recipes.data.repository

import android.util.Log
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.RecipeIdAndName
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for recipe data. Delegates all storage to Firestore via [FirestoreService].
 * Firestore's built-in offline cache provides offline access when network is disabled.
 */
@Singleton
class RecipeRepository @Inject constructor(
    private val firestoreService: FirestoreService
) {
    companion object {
        private const val TAG = "RecipeRepository"
    }

    private val _errors = MutableSharedFlow<RepositoryError>(extraBufferCapacity = 1)
    val errors: SharedFlow<RepositoryError> = _errors.asSharedFlow()

    /**
     * Observe all recipes in real-time.
     */
    fun getAllRecipes(): Flow<List<Recipe>> {
        return firestoreService.observeRecipes().map { remoteRecipes ->
            remoteRecipes.map { it.recipe }
        }
    }

    /**
     * Observe a single recipe by ID in real-time.
     */
    fun getRecipeById(id: String): Flow<Recipe?> {
        return firestoreService.observeRecipeById(id).map { it?.recipe }
    }

    /**
     * Get a recipe by ID (one-shot).
     */
    suspend fun getRecipeByIdOnce(id: String): Recipe? {
        return firestoreService.getRecipeById(id)?.recipe
    }

    /**
     * Get the original HTML stored with a recipe.
     */
    suspend fun getOriginalHtml(id: String): String? {
        return firestoreService.getRecipeById(id)?.originalHtml
    }

    /**
     * Get all recipe IDs and names for deduplication checks.
     */
    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> {
        return firestoreService.getAllRecipeIdsAndNames()
    }

    /**
     * Save a recipe (create or update) in Firestore.
     */
    suspend fun saveRecipe(recipe: Recipe, originalHtml: String? = null) {
        val result = firestoreService.upsertRecipe(recipe, originalHtml)
        if (result.isFailure) {
            Log.e(TAG, "Failed to save recipe: ${recipe.name}", result.exceptionOrNull())
        }
    }

    /**
     * Delete a recipe document from Firestore.
     */
    suspend fun deleteRecipe(id: String) {
        val result = firestoreService.deleteRecipe(id)
        if (result.isFailure) {
            Log.e(TAG, "Failed to delete recipe: $id", result.exceptionOrNull())
        }
    }

    /**
     * Set the favorite status of a recipe.
     */
    suspend fun setFavorite(id: String, isFavorite: Boolean) {
        val result = firestoreService.setFavorite(id, isFavorite)
        if (result.isFailure) {
            Log.e(TAG, "Failed to set favorite for recipe: $id", result.exceptionOrNull())
        }
    }
}
