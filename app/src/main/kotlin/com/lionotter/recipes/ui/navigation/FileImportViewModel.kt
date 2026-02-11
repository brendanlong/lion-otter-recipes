package com.lionotter.recipes.ui.navigation

import android.content.Context
import android.net.Uri
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.util.RecipeSerializer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.util.zip.ZipInputStream
import javax.inject.Inject
import androidx.lifecycle.ViewModel

/**
 * ViewModel for handling .lorecipes file imports.
 * Reads the ZIP file directly (single recipe, fast operation) rather than
 * going through WorkManager, so we can navigate to the imported recipe immediately.
 */
@HiltViewModel
class FileImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recipeRepository: RecipeRepository,
    private val recipeSerializer: RecipeSerializer,
    private val imageDownloadService: ImageDownloadService
) : ViewModel() {

    sealed class ImportResult {
        data class Success(val importedRecipeId: String?) : ImportResult()
        data class AlreadyExists(val existingRecipeId: String?) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    private suspend fun downloadImageIfNeeded(imageUrl: String?): String? {
        if (imageUrl == null) return null
        if (imageUrl.startsWith("file://")) {
            val path = imageUrl.removePrefix("file://")
            return if (java.io.File(path).exists()) imageUrl else null
        }
        return imageDownloadService.downloadAndStore(imageUrl)
    }

    /**
     * Import recipes from a .lorecipes (ZIP) file URI.
     * Returns the result of the import operation.
     */
    suspend fun importFromFile(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val inputStream = try {
            context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Error("Could not open file")
        } catch (e: Exception) {
            return@withContext ImportResult.Error("Failed to open file: ${e.message}")
        }

        val folderContents = mutableMapOf<String, MutableMap<String, String>>()

        try {
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val pathParts = entry.name.split("/", limit = 2)
                        if (pathParts.size == 2) {
                            val folderName = pathParts[0]
                            val fileName = pathParts[1]
                            val content = zipIn.readBytes().toString(Charsets.UTF_8)
                            folderContents.getOrPut(folderName) { mutableMapOf() }[fileName] = content
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            return@withContext ImportResult.Error("Failed to read file: ${e.message}")
        }

        if (folderContents.isEmpty()) {
            return@withContext ImportResult.Error("No recipes found in file")
        }

        var importedId: String? = null
        var existingId: String? = null

        for ((_, files) in folderContents) {
            val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME] ?: continue

            try {
                val recipe = recipeSerializer.deserializeRecipe(jsonContent)

                // Check if recipe already exists
                val existing = recipeRepository.getRecipeByIdOnce(recipe.id)
                if (existing != null) {
                    existingId = recipe.id
                    continue
                }

                val localImageUrl = downloadImageIfNeeded(recipe.imageUrl)
                val importedRecipe = recipe.copy(
                    updatedAt = Clock.System.now(),
                    imageUrl = localImageUrl
                )
                val originalHtml = files[RecipeSerializer.RECIPE_HTML_FILENAME]
                recipeRepository.saveRecipe(importedRecipe, originalHtml)
                importedId = recipe.id
            } catch (e: Exception) {
                return@withContext ImportResult.Error("Failed to import recipe: ${e.message}")
            }
        }

        when {
            importedId != null -> ImportResult.Success(importedId)
            existingId != null -> ImportResult.AlreadyExists(existingId)
            else -> ImportResult.Error("No valid recipes found in file")
        }
    }
}
