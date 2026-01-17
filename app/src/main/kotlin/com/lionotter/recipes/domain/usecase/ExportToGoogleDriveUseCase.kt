package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Use case for exporting recipes to Google Drive.
 * Creates a subfolder for each recipe containing:
 * - recipe.json: The structured recipe data
 * - original.html: The original HTML page (if available)
 * - recipe.md: A human-readable Markdown version
 */
class ExportToGoogleDriveUseCase @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val recipeRepository: RecipeRepository,
    private val json: Json
) {
    sealed class ExportResult {
        data class Success(
            val exportedCount: Int,
            val failedCount: Int,
            val rootFolder: DriveFolder
        ) : ExportResult()

        data class Error(val message: String) : ExportResult()
        object NotSignedIn : ExportResult()
    }

    sealed class ExportProgress {
        object Starting : ExportProgress()
        data class CreatingRootFolder(val folderName: String) : ExportProgress()
        data class ExportingRecipe(
            val recipeName: String,
            val current: Int,
            val total: Int
        ) : ExportProgress()

        data class Complete(val result: ExportResult) : ExportProgress()
    }

    /**
     * Export all recipes to Google Drive.
     *
     * @param parentFolderId Optional parent folder ID. If null, creates in root.
     * @param rootFolderName Name for the export folder (default: "Lion+Otter Recipes Export")
     * @param onProgress Callback for progress updates
     */
    suspend fun exportAllRecipes(
        parentFolderId: String? = null,
        rootFolderName: String = "Lion+Otter Recipes Export",
        onProgress: suspend (ExportProgress) -> Unit = {}
    ): ExportResult {
        if (!googleDriveService.isSignedIn()) {
            return ExportResult.NotSignedIn
        }

        onProgress(ExportProgress.Starting)

        // Get all recipes
        val recipes = recipeRepository.getAllRecipes().first()
        if (recipes.isEmpty()) {
            return ExportResult.Error("No recipes to export")
        }

        // Create or find the root export folder
        onProgress(ExportProgress.CreatingRootFolder(rootFolderName))
        val rootFolderResult = googleDriveService.findOrCreateFolder(rootFolderName, parentFolderId)
        if (rootFolderResult.isFailure) {
            return ExportResult.Error(
                "Failed to create export folder: ${rootFolderResult.exceptionOrNull()?.message}"
            )
        }
        val rootFolder = rootFolderResult.getOrThrow()

        var exportedCount = 0
        var failedCount = 0

        // Export each recipe
        recipes.forEachIndexed { index, recipe ->
            onProgress(
                ExportProgress.ExportingRecipe(
                    recipeName = recipe.name,
                    current = index + 1,
                    total = recipes.size
                )
            )

            val success = exportRecipe(recipe, rootFolder.id)
            if (success) {
                exportedCount++
            } else {
                failedCount++
            }
        }

        val result = ExportResult.Success(
            exportedCount = exportedCount,
            failedCount = failedCount,
            rootFolder = rootFolder
        )
        onProgress(ExportProgress.Complete(result))
        return result
    }

    /**
     * Export a single recipe to Google Drive.
     *
     * @param recipeId The recipe ID to export
     * @param parentFolderId The folder to export into
     */
    suspend fun exportSingleRecipe(
        recipeId: String,
        parentFolderId: String
    ): Result<DriveFolder> {
        if (!googleDriveService.isSignedIn()) {
            return Result.failure(IllegalStateException("Not signed in to Google Drive"))
        }

        val recipe = recipeRepository.getRecipeByIdOnce(recipeId)
            ?: return Result.failure(IllegalArgumentException("Recipe not found: $recipeId"))

        return if (exportRecipe(recipe, parentFolderId)) {
            // Return the folder that was created
            googleDriveService.listFolders(parentFolderId).map { folders ->
                folders.find { it.name == sanitizeFolderName(recipe.name) }
                    ?: throw IllegalStateException("Failed to find exported folder")
            }
        } else {
            Result.failure(Exception("Failed to export recipe"))
        }
    }

    private suspend fun exportRecipe(recipe: Recipe, parentFolderId: String): Boolean {
        return try {
            // Create folder for this recipe
            val folderName = sanitizeFolderName(recipe.name)
            val folderResult = googleDriveService.createFolder(folderName, parentFolderId)
            if (folderResult.isFailure) {
                return false
            }
            val recipeFolder = folderResult.getOrThrow()

            // Upload recipe.json
            val recipeJson = json.encodeToString(recipe)
            val jsonResult = googleDriveService.uploadJsonFile(
                fileName = GoogleDriveService.RECIPE_JSON_FILENAME,
                content = recipeJson,
                parentFolderId = recipeFolder.id
            )
            if (jsonResult.isFailure) {
                return false
            }

            // Upload original.html (if available)
            val originalHtml = recipeRepository.getOriginalHtml(recipe.id)
            if (originalHtml != null) {
                googleDriveService.uploadHtmlFile(
                    fileName = GoogleDriveService.RECIPE_HTML_FILENAME,
                    content = originalHtml,
                    parentFolderId = recipeFolder.id
                )
                // Don't fail if HTML upload fails - it's optional
            }

            // Upload recipe.md
            val markdown = RecipeMarkdownFormatter.format(recipe)
            val mdResult = googleDriveService.uploadMarkdownFile(
                fileName = GoogleDriveService.RECIPE_MARKDOWN_FILENAME,
                content = markdown,
                parentFolderId = recipeFolder.id
            )
            if (mdResult.isFailure) {
                return false
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sanitize a recipe name for use as a folder name.
     */
    private fun sanitizeFolderName(name: String): String {
        return name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_") // Replace invalid characters
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
            .take(100) // Limit length
            .ifEmpty { "Untitled Recipe" }
    }
}
