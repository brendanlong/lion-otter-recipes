package com.lionotter.recipes.domain.repository

import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.RecipeIdAndName
import kotlinx.coroutines.flow.Flow

/**
 * Recipes for the current user.
 *
 * Write contract: the non-suspending writes ([saveRecipe], [deleteRecipe], [setFavorite],
 * [setUserNotes], [setImageUrl], [updateTitleAndUrl]) apply to the local cache immediately (so
 * flows reflect them at once, even offline) and never throw. They sync in the background, and a
 * failure is reported asynchronously through the data layer's failure stream
 * (`FirestoreService.failures`), which the app shows wherever the user is. Callers therefore
 * need no try/catch, `NonCancellable` or error handling of their own. Suspending functions do throw on failure.
 */
interface RecipeRepository {
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
