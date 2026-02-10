package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import javax.inject.Inject

/**
 * Use case for bidirectional sync of recipes with Firebase Firestore.
 *
 * Sync strategy:
 * - Each recipe is stored as a Firestore document: users/{userId}/recipes/{recipeId}
 * - On sync, compares local recipes vs remote recipe documents
 * - New local recipes are uploaded to Firestore
 * - New remote recipes (not in local DB) are downloaded
 * - Updated recipes use "latest timestamp wins" conflict resolution
 * - Locally soft-deleted recipes are removed from Firestore
 */
class FirestoreSyncUseCase @Inject constructor(
    private val firestoreService: FirestoreService,
    private val recipeRepository: RecipeRepository,
    private val settingsDataStore: SettingsDataStore,
    private val syncMealPlansUseCase: FirestoreMealPlanSyncUseCase
) {
    companion object {
        private const val TAG = "FirestoreSync"
    }

    sealed class SyncResult {
        data class Success(
            val uploaded: Int,
            val downloaded: Int,
            val updated: Int,
            val deleted: Int
        ) : SyncResult()

        data class Error(val message: String) : SyncResult()
        object NotSignedIn : SyncResult()
        object SyncDisabled : SyncResult()
    }

    sealed class SyncProgress {
        object Starting : SyncProgress()
        object ComparingRecipes : SyncProgress()
        data class Uploading(val recipeName: String, val current: Int, val total: Int) : SyncProgress()
        data class Downloading(val recipeName: String, val current: Int, val total: Int) : SyncProgress()
        data class Updating(val recipeName: String, val current: Int, val total: Int) : SyncProgress()
        data class Deleting(val recipeName: String, val current: Int, val total: Int) : SyncProgress()
        data class Complete(val result: SyncResult) : SyncProgress()
    }

    /**
     * Perform a full bidirectional sync.
     */
    suspend fun sync(
        onProgress: suspend (SyncProgress) -> Unit = {}
    ): SyncResult {
        if (!firestoreService.isSignedIn()) {
            return SyncResult.NotSignedIn
        }

        val syncEnabled = settingsDataStore.firebaseSyncEnabled.first()
        if (!syncEnabled) {
            return SyncResult.SyncDisabled
        }

        onProgress(SyncProgress.Starting)

        return try {
            performSync(onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            SyncResult.Error("Sync failed: ${e.message}")
        }
    }

    private suspend fun performSync(
        onProgress: suspend (SyncProgress) -> Unit
    ): SyncResult {
        onProgress(SyncProgress.ComparingRecipes)

        // Get local recipes (active only)
        val localRecipes = recipeRepository.getAllRecipes().first()
        val localRecipeMap = localRecipes.associateBy { it.id }

        // Get soft-deleted local recipes (need to remove from remote)
        val deletedRecipes = recipeRepository.getDeletedRecipes()
        val deletedIds = deletedRecipes.map { it.id }.toSet()

        // Get remote recipes from Firestore
        val remoteResult = firestoreService.getAllRecipes()
        if (remoteResult.isFailure) {
            return SyncResult.Error(
                "Failed to fetch remote recipes: ${remoteResult.exceptionOrNull()?.message}"
            )
        }
        val remoteRecipes = remoteResult.getOrThrow()
        val remoteRecipeMap = remoteRecipes.associateBy { it.recipe.id }

        val localIds = localRecipeMap.keys
        val remoteIds = remoteRecipeMap.keys

        // Recipes only on local -> upload
        val toUpload = localIds - remoteIds
        // Recipes only on remote and NOT soft-deleted locally -> download
        val toDownload = (remoteIds - localIds) - deletedIds
        // Recipes on both -> check for updates
        val onBoth = localIds.intersect(remoteIds)

        var uploaded = 0
        var downloaded = 0
        var updated = 0
        var deleted = 0

        // Upload new local recipes
        val uploadList = toUpload.mapNotNull { localRecipeMap[it] }
        uploadList.forEachIndexed { index, recipe ->
            onProgress(SyncProgress.Uploading(recipe.name, index + 1, uploadList.size))
            val originalHtml = recipeRepository.getOriginalHtml(recipe.id)
            if (firestoreService.upsertRecipe(recipe, originalHtml).isSuccess) {
                uploaded++
            }
        }

        // Download new remote recipes
        val downloadList = toDownload.mapNotNull { remoteRecipeMap[it] }
        downloadList.forEachIndexed { index, remoteRecipe ->
            onProgress(SyncProgress.Downloading(remoteRecipe.recipe.name, index + 1, downloadList.size))
            try {
                recipeRepository.saveRecipe(remoteRecipe.recipe, remoteRecipe.originalHtml)
                downloaded++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save downloaded recipe ${remoteRecipe.recipe.name}", e)
            }
        }

        // Handle recipes that exist on both sides - update if needed
        val updateList = onBoth.toList()
        updateList.forEachIndexed { index, recipeId ->
            val localRecipe = localRecipeMap[recipeId] ?: return@forEachIndexed
            val remoteRecipe = remoteRecipeMap[recipeId] ?: return@forEachIndexed

            onProgress(SyncProgress.Updating(localRecipe.name, index + 1, updateList.size))

            // Compare timestamps - latest wins
            if (localRecipe.updatedAt > remoteRecipe.recipe.updatedAt) {
                // Local is newer, update remote
                val originalHtml = recipeRepository.getOriginalHtml(recipeId)
                if (firestoreService.upsertRecipe(localRecipe, originalHtml).isSuccess) {
                    updated++
                }
            } else if (remoteRecipe.recipe.updatedAt > localRecipe.updatedAt) {
                // Remote is newer, update local
                try {
                    recipeRepository.saveRecipe(remoteRecipe.recipe, remoteRecipe.originalHtml)
                    updated++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update local recipe ${localRecipe.name}", e)
                }
            }
            // If equal timestamps, no update needed
        }

        // Delete remote recipes that were soft-deleted locally
        val toDeleteFromRemote = deletedIds.filter { remoteRecipeMap.containsKey(it) }
        toDeleteFromRemote.forEachIndexed { index, recipeId ->
            val remoteRecipe = remoteRecipeMap[recipeId] ?: return@forEachIndexed
            onProgress(SyncProgress.Deleting(remoteRecipe.recipe.name, index + 1, toDeleteFromRemote.size))
            if (firestoreService.hardDeleteRecipe(recipeId).isSuccess) {
                deleted++
            }
        }

        // Purge soft-deleted recipes now that sync is complete
        if (deletedRecipes.isNotEmpty()) {
            recipeRepository.purgeDeletedRecipes()
        }

        // Sync meal plans
        val mealPlanResult = syncMealPlansUseCase.sync()
        uploaded += mealPlanResult.uploaded
        downloaded += mealPlanResult.downloaded
        updated += mealPlanResult.updated
        deleted += mealPlanResult.deleted

        // Update last sync timestamp
        settingsDataStore.setFirebaseLastSyncTimestamp(Clock.System.now().toString())

        val result = SyncResult.Success(
            uploaded = uploaded,
            downloaded = downloaded,
            updated = updated,
            deleted = deleted
        )
        onProgress(SyncProgress.Complete(result))
        return result
    }
}
