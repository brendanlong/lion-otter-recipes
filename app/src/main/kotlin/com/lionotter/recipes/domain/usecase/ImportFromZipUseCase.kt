package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.domain.util.ZipImportHelper
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Use case for importing recipes from a ZIP file.
 * Expects the same folder structure as the export:
 * - recipe-name/recipe.json
 * - recipe-name/original.html (optional)
 * - recipe-name/recipe.md (ignored on import, regenerated from data)
 * - recipe-name/image.* (recipe image, used if present)
 *
 * Import strategy: JSON-first.
 * Skips recipes that already exist locally (by ID).
 */
class ImportFromZipUseCase @Inject constructor(
    private val zipImportHelper: ZipImportHelper,
    private val recipeSerializer: RecipeSerializer
) {
    companion object {
        private const val TAG = "ImportFromZipUseCase"
    }
    sealed class ImportResult {
        data class Success(
            val importedCount: Int,
            val failedCount: Int,
            val skippedCount: Int
        ) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    sealed class ImportProgress {
        object Starting : ImportProgress()
        object ReadingZip : ImportProgress()
        data class ImportingRecipe(
            val recipeName: String,
            val current: Int,
            val total: Int
        ) : ImportProgress()
        data class Complete(val result: ImportResult) : ImportProgress()
    }

    /**
     * Import recipes from a ZIP file input stream.
     * @param selectedRecipeIds If non-null, only import recipes whose IDs are in this set.
     */
    suspend fun importFromZip(
        inputStream: InputStream,
        selectedRecipeIds: Set<String>? = null,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        onProgress(ImportProgress.Starting)
        onProgress(ImportProgress.ReadingZip)

        val zipContents = zipImportHelper.readZipContents(inputStream)
            ?: return ImportResult.Error("Failed to read ZIP file or no data found")

        var importedCount = 0
        var failedCount = 0
        var skippedCount = 0

        val recipeFolders = zipContents.textFiles.entries
            .filter { it.key != ZipImportHelper.MEAL_PLANS_FOLDER }
            .let { entries ->
                if (selectedRecipeIds != null) {
                    // Pre-filter to only selected recipes by deserializing to check ID
                    entries.filter { (_, files) ->
                        val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME] ?: return@filter false
                        try {
                            val recipe = recipeSerializer.deserializeRecipe(jsonContent)
                            recipe.id in selectedRecipeIds
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to deserialize recipe during pre-filter, including for error reporting", e)
                            true // include failures so they're counted as failed
                        }
                    }
                } else {
                    entries
                }
            }
            .toList()

        recipeFolders.forEachIndexed { index, (folderName, files) ->
            coroutineContext.ensureActive()

            onProgress(
                ImportProgress.ImportingRecipe(
                    recipeName = folderName,
                    current = index + 1,
                    total = recipeFolders.size
                )
            )

            val imageFiles = zipContents.imageFiles[folderName] ?: emptyMap()
            when (zipImportHelper.importRecipe(files, imageFiles)) {
                is ZipImportHelper.SingleRecipeResult.Imported -> importedCount++
                is ZipImportHelper.SingleRecipeResult.Skipped -> skippedCount++
                is ZipImportHelper.SingleRecipeResult.NoJson -> failedCount++
                is ZipImportHelper.SingleRecipeResult.Failed -> failedCount++
            }
        }

        // Import meal plans from the meal-plans folder (always, not filtered by selection)
        val mealPlanFiles = zipContents.textFiles[ZipImportHelper.MEAL_PLANS_FOLDER]
        if (mealPlanFiles != null) {
            val mealImported = zipImportHelper.importMealPlans(mealPlanFiles)
            importedCount += mealImported
        }

        val result = ImportResult.Success(
            importedCount = importedCount,
            failedCount = failedCount,
            skippedCount = skippedCount
        )
        onProgress(ImportProgress.Complete(result))
        return result
    }
}
