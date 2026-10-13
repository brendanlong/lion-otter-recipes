package com.lionotter.recipes.domain.repository

import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.RecipeIdAndName
import kotlinx.coroutines.flow.Flow

/**
 * Recipes for the current user.
 *
 * Write contract: the non-suspending writes ([saveRecipe], [deleteRecipe], [setFavorite],
 * [setUserNotes], [setImageUrl], [setImagePrompt], [updateTitleAndUrl], [setTags]) apply to the local cache immediately (so
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
    fun setImagePrompt(id: String, imagePrompt: String?)
    fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?)
    fun setTags(id: String, tags: List<String>)
    suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName>

    /** Every recipe's tags, by recipe ID, as stored on this device (never waits for the network). */
    suspend fun getAllRecipeTags(): Map<String, List<String>>
    suspend fun getRecipeCount(): Int

    /** True if a recipe other than [excludingRecipeId] has [imageUrl] as its image. */
    suspend fun isImageReferenced(imageUrl: String, excludingRecipeId: String? = null): Boolean

    /** Recipe [id]'s image URL as stored on this device (no server round trip), or null. */
    suspend fun getImageUrl(id: String): String?

    /**
     * The image URL of every recipe that has one, by recipe ID. With [localOnly], only what is
     * already on this device is read (no server round trip).
     */
    suspend fun getImageUrlsByRecipeId(localOnly: Boolean = false): Map<String, String>

    /**
     * Sets recipe [id]'s image to [replacement] only if it is still [expected], atomically on the
     * server (so it needs to be online). Returns whether it was replaced.
     */
    suspend fun replaceImageUrl(id: String, expected: String, replacement: String): Boolean
}
