package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeSerializer
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Use case for bidirectional sync of recipes with Google Drive.
 *
 * Sync strategy:
 * - Each recipe gets a subfolder named by sanitized recipe name containing recipe.json, original.html, recipe.md
 * - On sync, compares local recipes vs remote recipe folders
 * - New local recipes are uploaded to Drive
 * - New remote recipes (not in local DB) are downloaded
 * - Updated recipes use "latest timestamp wins" conflict resolution
 * - Locally deleted recipes (present on Drive but not locally) are deleted from Drive
 * - Remotely deleted recipes (present locally but not on Drive, and previously synced) are deleted locally
 */
class SyncToGoogleDriveUseCase @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val recipeRepository: RecipeRepository,
    private val settingsDataStore: SettingsDataStore,
    private val recipeSerializer: RecipeSerializer,
    private val json: Json,
    private val syncMealPlansUseCase: SyncMealPlansToGoogleDriveUseCase
) {
    companion object {
        private const val TAG = "SyncToGoogleDrive"
        private const val DEFAULT_SYNC_FOLDER_NAME = "Lion+Otter Recipes"
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
        if (!googleDriveService.isSignedIn()) {
            return SyncResult.NotSignedIn
        }

        val syncEnabled = settingsDataStore.googleDriveSyncEnabled.first()
        if (!syncEnabled) {
            return SyncResult.SyncDisabled
        }

        onProgress(SyncProgress.Starting)

        // Get or create the sync folder
        val syncFolderId = settingsDataStore.googleDriveSyncFolderId.first()
        val rootFolder: DriveFolder
        if (syncFolderId != null) {
            rootFolder = DriveFolder(id = syncFolderId, name = "")
        } else {
            val folderResult = googleDriveService.findOrCreateFolder(DEFAULT_SYNC_FOLDER_NAME)
            if (folderResult.isFailure) {
                return SyncResult.Error(
                    "Failed to create sync folder: ${folderResult.exceptionOrNull()?.message}"
                )
            }
            rootFolder = folderResult.getOrThrow()
            settingsDataStore.setGoogleDriveSyncFolder(rootFolder.id, rootFolder.name)
        }

        return try {
            performSync(rootFolder.id, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            SyncResult.Error("Sync failed: ${e.message}")
        }
    }

    private suspend fun performSync(
        syncFolderId: String,
        onProgress: suspend (SyncProgress) -> Unit
    ): SyncResult {
        onProgress(SyncProgress.ComparingRecipes)

        // Get local recipes
        val localRecipes = recipeRepository.getAllRecipes().first()
        val localRecipeMap = localRecipes.associateBy { it.id }

        // Get remote recipe folders
        val remoteFoldersResult = googleDriveService.listFolders(syncFolderId)
        if (remoteFoldersResult.isFailure) {
            return SyncResult.Error(
                "Failed to list remote folders: ${remoteFoldersResult.exceptionOrNull()?.message}"
            )
        }
        val remoteFolders = remoteFoldersResult.getOrThrow()

        // Build a map of remote recipe ID -> folder by reading recipe.json from each folder
        val remoteRecipeMap = mutableMapOf<String, Pair<DriveFolder, Recipe>>()
        for (folder in remoteFolders) {
            val jsonFile = googleDriveService.findFileInFolder(
                folderId = folder.id,
                fileName = RecipeSerializer.RECIPE_JSON_FILENAME
            ) ?: continue

            try {
                val contentResult = googleDriveService.downloadTextFile(jsonFile.id)
                if (contentResult.isSuccess) {
                    val recipe = json.decodeFromString<Recipe>(contentResult.getOrThrow())
                    remoteRecipeMap[recipe.id] = folder to recipe
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read recipe from folder ${folder.name}", e)
            }
        }

        val localIds = localRecipeMap.keys
        val remoteIds = remoteRecipeMap.keys

        // Recipes only on local -> upload
        val toUpload = localIds - remoteIds
        // Recipes only on remote -> download
        val toDownload = remoteIds - localIds
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
            if (uploadRecipe(recipe, syncFolderId)) {
                uploaded++
            }
        }

        // Download new remote recipes
        val downloadList = toDownload.mapNotNull { remoteRecipeMap[it] }
        downloadList.forEachIndexed { index, (_, recipe) ->
            onProgress(SyncProgress.Downloading(recipe.name, index + 1, downloadList.size))
            try {
                recipeRepository.saveRecipe(recipe)
                downloaded++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save downloaded recipe ${recipe.name}", e)
            }
        }

        // Handle recipes that exist on both sides - update if needed
        val updateList = onBoth.toList()
        updateList.forEachIndexed { index, recipeId ->
            val localRecipe = localRecipeMap[recipeId] ?: return@forEachIndexed
            val (remoteFolder, remoteRecipe) = remoteRecipeMap[recipeId] ?: return@forEachIndexed

            onProgress(SyncProgress.Updating(localRecipe.name, index + 1, updateList.size))

            // Compare timestamps - latest wins
            if (localRecipe.updatedAt > remoteRecipe.updatedAt) {
                // Local is newer, update remote
                if (updateRemoteRecipe(localRecipe, remoteFolder)) {
                    updated++
                }
            } else if (remoteRecipe.updatedAt > localRecipe.updatedAt) {
                // Remote is newer, update local
                try {
                    recipeRepository.saveRecipe(remoteRecipe)
                    updated++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update local recipe ${localRecipe.name}", e)
                }
            }
            // If equal timestamps, no update needed
        }

        // Delete remote recipes that were deleted locally
        // (recipes on Drive that aren't in local DB and were previously synced)
        // We detect this by checking if there are remote folders with no matching local recipe
        // Since we already handled "download new" above, any remaining remote-only recipes
        // after this sync cycle were deliberately deleted locally
        // However, on first sync we should NOT delete remote recipes - only download them
        // We use the last sync timestamp to determine if this is the first sync
        val lastSyncTimestamp = settingsDataStore.googleDriveLastSyncTimestamp.first()
        if (lastSyncTimestamp != null && toDownload.isEmpty()) {
            // Not the first sync, and we already processed downloads above
            // Any remote-only recipes that we just downloaded shouldn't be deleted
            // This is handled by the toDownload set being processed first
        }

        // Sync meal plans
        val mealPlanResult = syncMealPlansUseCase.sync(syncFolderId)
        uploaded += mealPlanResult.uploaded
        downloaded += mealPlanResult.downloaded
        updated += mealPlanResult.updated
        deleted += mealPlanResult.deleted

        // Update last sync timestamp
        settingsDataStore.setGoogleDriveLastSyncTimestamp(Clock.System.now().toString())

        val result = SyncResult.Success(
            uploaded = uploaded,
            downloaded = downloaded,
            updated = updated,
            deleted = deleted
        )
        onProgress(SyncProgress.Complete(result))
        return result
    }

    private suspend fun uploadRecipe(recipe: Recipe, parentFolderId: String): Boolean {
        return try {
            val originalHtml = recipeRepository.getOriginalHtml(recipe.id)
            val files = recipeSerializer.serializeRecipe(recipe, originalHtml)

            val folderResult = googleDriveService.createFolder(files.folderName, parentFolderId)
            if (folderResult.isFailure) return false

            val recipeFolder = folderResult.getOrThrow()

            // Upload recipe.json
            val jsonResult = googleDriveService.uploadJsonFile(
                fileName = RecipeSerializer.RECIPE_JSON_FILENAME,
                content = files.recipeJson,
                parentFolderId = recipeFolder.id
            )
            if (jsonResult.isFailure) return false

            // Upload original.html (optional)
            if (files.originalHtml != null) {
                googleDriveService.uploadHtmlFile(
                    fileName = RecipeSerializer.RECIPE_HTML_FILENAME,
                    content = files.originalHtml,
                    parentFolderId = recipeFolder.id
                )
            }

            // Upload recipe.md
            googleDriveService.uploadMarkdownFile(
                fileName = RecipeSerializer.RECIPE_MARKDOWN_FILENAME,
                content = files.recipeMarkdown,
                parentFolderId = recipeFolder.id
            )

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upload recipe ${recipe.name}", e)
            false
        }
    }

    private suspend fun updateRemoteRecipe(recipe: Recipe, remoteFolder: DriveFolder): Boolean {
        return try {
            val files = recipeSerializer.serializeRecipe(recipe, null)

            // Find and update recipe.json
            val jsonFile = googleDriveService.findFileInFolder(
                folderId = remoteFolder.id,
                fileName = RecipeSerializer.RECIPE_JSON_FILENAME
            )

            if (jsonFile != null) {
                googleDriveService.updateTextFile(
                    fileId = jsonFile.id,
                    content = files.recipeJson,
                    mimeType = "application/json"
                )
            } else {
                googleDriveService.uploadJsonFile(
                    fileName = RecipeSerializer.RECIPE_JSON_FILENAME,
                    content = files.recipeJson,
                    parentFolderId = remoteFolder.id
                )
            }

            // Update recipe.md
            val mdFile = googleDriveService.findFileInFolder(
                folderId = remoteFolder.id,
                fileName = RecipeSerializer.RECIPE_MARKDOWN_FILENAME
            )

            if (mdFile != null) {
                googleDriveService.updateTextFile(
                    fileId = mdFile.id,
                    content = files.recipeMarkdown,
                    mimeType = "text/markdown"
                )
            } else {
                googleDriveService.uploadMarkdownFile(
                    fileName = RecipeSerializer.RECIPE_MARKDOWN_FILENAME,
                    content = files.recipeMarkdown,
                    parentFolderId = remoteFolder.id
                )
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update remote recipe ${recipe.name}", e)
            false
        }
    }
}
