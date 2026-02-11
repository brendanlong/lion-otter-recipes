package com.lionotter.recipes.domain.util

import android.util.Log
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Shared helper for importing recipes and meal plans from ZIP files.
 *
 * Consolidates the common logic used by ImportFromZipUseCase, FileImportViewModel,
 * and ImportSelectionViewModel:
 * - Reading ZIP entries into a folder-grouped map
 * - Importing a single recipe (deserialize, deduplicate, download image, save)
 * - Importing meal plan entries from the meal-plans folder
 */
class ZipImportHelper @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val recipeSerializer: RecipeSerializer,
    private val mealPlanRepository: MealPlanRepository,
    private val json: Json,
    private val imageDownloadService: ImageDownloadService
) {
    companion object {
        private const val TAG = "ZipImportHelper"
        const val MEAL_PLANS_FOLDER = "meal-plans"
    }

    /**
     * Result of importing a single recipe from a ZIP folder.
     */
    sealed class SingleRecipeResult {
        data class Imported(val recipeId: String) : SingleRecipeResult()
        data class Skipped(val recipeId: String) : SingleRecipeResult()
        object NoJson : SingleRecipeResult()
        data class Failed(val error: Exception) : SingleRecipeResult()
    }

    /**
     * Reads all entries from a ZIP input stream into a folder-grouped map.
     * Each top-level folder becomes a key, and its files become a nested map
     * of filename to content.
     *
     * @return map of folder name to (filename to content), or null if reading fails
     */
    fun readZipContents(inputStream: InputStream): Map<String, Map<String, String>>? {
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
            return null
        }

        return folderContents.ifEmpty { null }
    }

    /**
     * Imports a single recipe from its folder files.
     *
     * Handles deserialization, duplicate checking (by ID), image downloading,
     * and saving to the repository.
     *
     * @param files map of filename to content for a single recipe folder
     * @return the result of the import attempt
     */
    suspend fun importRecipe(files: Map<String, String>): SingleRecipeResult {
        val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME]
            ?: return SingleRecipeResult.NoJson

        return try {
            val recipe = recipeSerializer.deserializeRecipe(jsonContent)

            val existing = recipeRepository.getRecipeByIdOnce(recipe.id)
            if (existing != null) {
                return SingleRecipeResult.Skipped(recipe.id)
            }

            val importedRecipe = recipe.copy(
                updatedAt = Clock.System.now(),
                imageUrl = imageDownloadService.downloadImageIfNeeded(recipe.imageUrl)
            )
            val originalHtml = files[RecipeSerializer.RECIPE_HTML_FILENAME]
            recipeRepository.saveRecipe(importedRecipe, originalHtml)
            SingleRecipeResult.Imported(recipe.id)
        } catch (e: Exception) {
            SingleRecipeResult.Failed(e)
        }
    }

    /**
     * Imports meal plan entries from the meal-plans folder in a ZIP backup.
     * Skips entries that already exist (by ID).
     *
     * @param mealPlanFiles map of filename to content from the meal-plans folder
     * @return pair of (imported count, skipped count)
     */
    suspend fun importMealPlans(mealPlanFiles: Map<String, String>): Pair<Int, Int> {
        var imported = 0
        var skipped = 0

        for ((fileName, content) in mealPlanFiles) {
            if (!fileName.endsWith(".json")) continue
            try {
                val entries = json.decodeFromString<List<MealPlanEntry>>(content)
                for (entry in entries) {
                    val existing = mealPlanRepository.getMealPlanByIdOnce(entry.id)
                    if (existing == null) {
                        mealPlanRepository.saveMealPlan(entry)
                        imported++
                    } else {
                        skipped++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import meal plan file $fileName", e)
            }
        }

        return Pair(imported, skipped)
    }
}
