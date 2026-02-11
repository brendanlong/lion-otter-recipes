package com.lionotter.recipes.ui.navigation

import android.content.Context
import android.net.Uri
import com.lionotter.recipes.domain.util.ZipImportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val zipImportHelper: ZipImportHelper
) : ViewModel() {

    sealed class ImportResult {
        data class Success(val importedRecipeId: String?) : ImportResult()
        data class AlreadyExists(val existingRecipeId: String?) : ImportResult()
        data class Error(val message: String) : ImportResult()
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

        val folderContents = zipImportHelper.readZipContents(inputStream)
            ?: return@withContext ImportResult.Error("No recipes found in file")

        var importedId: String? = null
        var existingId: String? = null

        for ((_, files) in folderContents) {
            when (val result = zipImportHelper.importRecipe(files)) {
                is ZipImportHelper.SingleRecipeResult.Imported -> importedId = result.recipeId
                is ZipImportHelper.SingleRecipeResult.Skipped -> existingId = result.recipeId
                is ZipImportHelper.SingleRecipeResult.NoJson -> continue
                is ZipImportHelper.SingleRecipeResult.Failed ->
                    return@withContext ImportResult.Error("Failed to import recipe: ${result.error.message}")
            }
        }

        when {
            importedId != null -> ImportResult.Success(importedId)
            existingId != null -> ImportResult.AlreadyExists(existingId)
            else -> ImportResult.Error("No valid recipes found in file")
        }
    }
}
