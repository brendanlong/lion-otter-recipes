package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.remote.DriveFile
import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Use case for importing recipes from Google Drive.
 * For each recipe folder, it:
 * 1. First tries to load recipe.json directly
 * 2. If JSON loading fails, falls back to importing the HTML via the standard AI process
 *
 * This reuses the existing ParseHtmlUseCase to avoid duplicate logic.
 */
class ImportFromGoogleDriveUseCase @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val recipeRepository: RecipeRepository,
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val json: Json
) {
    sealed class ImportResult {
        data class Success(
            val importedCount: Int,
            val failedCount: Int,
            val skippedCount: Int
        ) : ImportResult()

        data class Error(val message: String) : ImportResult()
        object NotSignedIn : ImportResult()
        object NoApiKey : ImportResult()
    }

    sealed class ImportProgress {
        object Starting : ImportProgress()
        object ListingFolders : ImportProgress()
        data class ImportingRecipe(
            val folderName: String,
            val method: ImportMethod,
            val current: Int,
            val total: Int
        ) : ImportProgress()

        data class Complete(val result: ImportResult) : ImportProgress()
    }

    enum class ImportMethod {
        JSON,           // Loaded directly from JSON
        HTML_FALLBACK   // Fell back to HTML parsing via AI
    }

    /**
     * Import all recipes from a Google Drive folder.
     *
     * @param folderId The ID of the folder containing recipe subfolders
     * @param onProgress Callback for progress updates
     */
    suspend fun importFromFolder(
        folderId: String,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        if (!googleDriveService.isSignedIn()) {
            return ImportResult.NotSignedIn
        }

        onProgress(ImportProgress.Starting)

        // List recipe folders
        onProgress(ImportProgress.ListingFolders)
        val foldersResult = googleDriveService.listRecipeFolders(folderId)
        if (foldersResult.isFailure) {
            return ImportResult.Error(
                "Failed to list folders: ${foldersResult.exceptionOrNull()?.message}"
            )
        }

        val recipeFolders = foldersResult.getOrThrow()
        if (recipeFolders.isEmpty()) {
            return ImportResult.Error("No recipe folders found")
        }

        var importedCount = 0
        var failedCount = 0
        var skippedCount = 0

        // Import each recipe folder
        recipeFolders.forEachIndexed { index, folder ->
            val importMethod = ImportMethod.JSON // Will be updated during import

            onProgress(
                ImportProgress.ImportingRecipe(
                    folderName = folder.name,
                    method = importMethod,
                    current = index + 1,
                    total = recipeFolders.size
                )
            )

            val result = importRecipeFromFolder(folder)
            when (result) {
                is SingleImportResult.Success -> importedCount++
                is SingleImportResult.Skipped -> skippedCount++
                is SingleImportResult.Failed -> failedCount++
                is SingleImportResult.NoApiKey -> {
                    // If we need API key for HTML fallback but don't have one, count as failed
                    failedCount++
                }
            }
        }

        val result = ImportResult.Success(
            importedCount = importedCount,
            failedCount = failedCount,
            skippedCount = skippedCount
        )
        onProgress(ImportProgress.Complete(result))
        return result
    }

    /**
     * Import a single recipe from a folder.
     */
    suspend fun importSingleRecipe(folderId: String): SingleImportResult {
        if (!googleDriveService.isSignedIn()) {
            return SingleImportResult.Failed("Not signed in to Google Drive")
        }

        // Get folder info
        val foldersResult = googleDriveService.listFolders()
        if (foldersResult.isFailure) {
            return SingleImportResult.Failed("Failed to access folder")
        }

        val folder = DriveFolder(id = folderId, name = "Recipe")
        return importRecipeFromFolder(folder)
    }

    private suspend fun importRecipeFromFolder(folder: DriveFolder): SingleImportResult {
        // Try to find recipe.json first
        val jsonFile = googleDriveService.findFileInFolder(
            folderId = folder.id,
            fileName = GoogleDriveService.RECIPE_JSON_FILENAME
        )

        if (jsonFile != null) {
            // Try to load from JSON
            val jsonResult = tryImportFromJson(jsonFile)
            if (jsonResult is SingleImportResult.Success || jsonResult is SingleImportResult.Skipped) {
                return jsonResult
            }
            // If JSON import failed, fall through to HTML fallback
        }

        // Try HTML fallback
        val htmlFile = googleDriveService.findFileInFolder(
            folderId = folder.id,
            fileName = GoogleDriveService.RECIPE_HTML_FILENAME
        )

        if (htmlFile != null) {
            return tryImportFromHtml(htmlFile, folder.name)
        }

        // Neither JSON nor HTML found
        return SingleImportResult.Failed("No recipe.json or original.html found in folder: ${folder.name}")
    }

    private suspend fun tryImportFromJson(jsonFile: DriveFile): SingleImportResult {
        return try {
            val contentResult = googleDriveService.downloadTextFile(jsonFile.id)
            if (contentResult.isFailure) {
                return SingleImportResult.Failed("Failed to download JSON: ${contentResult.exceptionOrNull()?.message}")
            }

            val jsonContent = contentResult.getOrThrow()
            val recipe = json.decodeFromString<Recipe>(jsonContent)

            // Check if recipe with this ID already exists locally
            val existingRecipe = recipeRepository.getRecipeByIdOnce(recipe.id)
            if (existingRecipe != null) {
                return SingleImportResult.Skipped("Recipe already exists: ${recipe.name}")
            }

            // Use original ID from JSON to maintain consistency across export/import cycles
            val importedRecipe = recipe.copy(
                updatedAt = kotlinx.datetime.Clock.System.now()
            )

            // Save to database without original HTML (we could download it but it's not essential)
            recipeRepository.saveRecipe(importedRecipe)

            SingleImportResult.Success(importedRecipe, ImportMethod.JSON)
        } catch (e: Exception) {
            SingleImportResult.Failed("Failed to parse JSON: ${e.message}")
        }
    }

    private suspend fun tryImportFromHtml(htmlFile: DriveFile, folderName: String): SingleImportResult {
        return try {
            val contentResult = googleDriveService.downloadTextFile(htmlFile.id)
            if (contentResult.isFailure) {
                return SingleImportResult.Failed("Failed to download HTML: ${contentResult.exceptionOrNull()?.message}")
            }

            val htmlContent = contentResult.getOrThrow()

            // Use the ParseHtmlUseCase to parse the HTML (reusing existing logic)
            val parseResult = parseHtmlUseCase.execute(
                html = htmlContent,
                sourceUrl = null,
                imageUrl = null,
                saveRecipe = true
            )

            when (parseResult) {
                is ParseHtmlUseCase.ParseResult.Success -> {
                    SingleImportResult.Success(parseResult.recipe, ImportMethod.HTML_FALLBACK)
                }
                is ParseHtmlUseCase.ParseResult.Error -> {
                    SingleImportResult.Failed(parseResult.message)
                }
                ParseHtmlUseCase.ParseResult.NoApiKey -> {
                    SingleImportResult.NoApiKey
                }
            }
        } catch (e: Exception) {
            SingleImportResult.Failed("Failed to import from HTML: ${e.message}")
        }
    }

    sealed class SingleImportResult {
        data class Success(val recipe: Recipe, val method: ImportMethod) : SingleImportResult()
        data class Skipped(val reason: String) : SingleImportResult()
        data class Failed(val reason: String) : SingleImportResult()
        object NoApiKey : SingleImportResult()
    }
}
