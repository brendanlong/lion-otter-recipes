package com.lionotter.recipes.data.repository

import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.Flow

/**
 * Recipes for the current user.
 *
 * Write contract: the non-suspending writes ([saveRecipe], [deleteRecipe], [setFavorite],
 * [setUserNotes], [setImageUrl], [updateTitleAndUrl]) apply to the local cache immediately (so
 * flows reflect them at once, even offline) and never throw. They sync in the background, and a
 * failure is reported asynchronously through
 * [FirestoreService.failures][com.lionotter.recipes.data.remote.FirestoreService.failures], which
 * the app shows wherever the user is. Callers therefore need no try/catch, `NonCancellable` or
 * error handling of their own. Suspending functions do throw on failure.
 */
interface IRecipeRepository {
    fun getAllRecipes(): Flow<List<Recipe>>
    fun getRecipeById(id: String): Flow<Recipe?>
    suspend fun getRecipeByIdOnce(id: String): Recipe?
    fun saveRecipe(recipe: Recipe)
    fun deleteRecipe(id: String)
    fun setFavorite(id: String, isFavorite: Boolean)
    fun setUserNotes(id: String, userNotes: String?)
    fun setImageUrl(id: String, imageUrl: String?)
    fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?)
    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName>
    suspend fun getRecipeCount(): Int
}
