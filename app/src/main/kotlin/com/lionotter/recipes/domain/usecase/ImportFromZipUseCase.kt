package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.util.RecipeSerializer
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Use case for importing recipes from a ZIP file.
 * Expects the same folder structure as the export:
 * - recipe-name/recipe.json
 * - recipe-name/original.html (optional)
 * - recipe-name/recipe.md (ignored on import, regenerated from data)
 *
 * Import strategy: JSON-first (same as Google Drive import).
 * Skips recipes that already exist locally (by ID).
 */
class ImportFromZipUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val recipeSerializer: RecipeSerializer,
    private val mealPlanRepository: MealPlanRepository,
    private val json: Json
) {
    companion object {
        private const val TAG = "ImportFromZip"
        private const val MEAL_PLANS_FOLDER = "meal-plans"
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
     */
    suspend fun importFromZip(
        inputStream: InputStream,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        onProgress(ImportProgress.Starting)
        onProgress(ImportProgress.ReadingZip)

        // First pass: read all entries from the ZIP into memory, grouped by folder
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
            return ImportResult.Error("Failed to read ZIP file: ${e.message}")
        }

        if (folderContents.isEmpty()) {
            return ImportResult.Error("No data found in ZIP file")
        }

        var importedCount = 0
        var failedCount = 0
        var skippedCount = 0

        val folders = folderContents.entries.filter { it.key != MEAL_PLANS_FOLDER }.toList()
        folders.forEachIndexed { index, (folderName, files) ->
            coroutineContext.ensureActive()

            onProgress(
                ImportProgress.ImportingRecipe(
                    recipeName = folderName,
                    current = index + 1,
                    total = folders.size
                )
            )

            val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME]
            if (jsonContent == null) {
                failedCount++
                return@forEachIndexed
            }

            try {
                val recipe = recipeSerializer.deserializeRecipe(jsonContent)

                // Check if recipe already exists
                val existing = recipeRepository.getRecipeByIdOnce(recipe.id)
                if (existing != null) {
                    skippedCount++
                    return@forEachIndexed
                }

                val importedRecipe = recipe.copy(updatedAt = Clock.System.now())
                val originalHtml = files[RecipeSerializer.RECIPE_HTML_FILENAME]
                recipeRepository.saveRecipe(importedRecipe, originalHtml)
                importedCount++
            } catch (e: Exception) {
                failedCount++
            }
        }

        // Import meal plans from the meal-plans folder
        val mealPlanFiles = folderContents[MEAL_PLANS_FOLDER]
        if (mealPlanFiles != null) {
            for ((fileName, content) in mealPlanFiles) {
                if (!fileName.endsWith(".json")) continue
                try {
                    val entries = json.decodeFromString<List<MealPlanEntry>>(content)
                    for (entry in entries) {
                        val existing = mealPlanRepository.getMealPlanByIdOnce(entry.id)
                        if (existing == null) {
                            mealPlanRepository.saveMealPlan(entry)
                            importedCount++
                        } else {
                            skippedCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import meal plan file $fileName", e)
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
}
