package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.util.RecipeSerializer
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Use case for exporting recipes to a ZIP file.
 * Uses a standard folder structure:
 * - recipe-name/recipe.json
 * - recipe-name/original.html (if available)
 * - recipe-name/recipe.md
 * - recipe-name/image.* (recipe image, if available)
 */
class ExportToZipUseCase @Inject constructor(
    private val recipeRepository: IRecipeRepository,
    private val recipeSerializer: RecipeSerializer,
    private val mealPlanRepository: IMealPlanRepository,
    private val imageDownloadService: ImageDownloadService,
    private val json: Json
) {
    companion object {
        private const val TAG = "ExportToZipUseCase"
        const val MEAL_PLANS_FOLDER = "meal-plans"
    }

    sealed class ExportResult {
        data class Success(val exportedCount: Int, val failedCount: Int) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    sealed class ExportProgress {
        object Starting : ExportProgress()
        data class ExportingRecipe(
            val recipeName: String,
            val current: Int,
            val total: Int
        ) : ExportProgress()
        data class Complete(val result: ExportResult) : ExportProgress()
    }

    /**
     * Export all recipes to a ZIP file written to the given output stream.
     */
    suspend fun exportAllRecipes(
        outputStream: OutputStream,
        onProgress: suspend (ExportProgress) -> Unit = {}
    ): ExportResult {
        onProgress(ExportProgress.Starting)

        val recipes = recipeRepository.getAllRecipes().first()
        if (recipes.isEmpty()) {
            return ExportResult.Error("No recipes to export")
        }

        var exportedCount = 0
        var failedCount = 0

        try {
            ZipOutputStream(outputStream).use { zipOut ->
                recipes.forEachIndexed { index, recipe ->
                    onProgress(
                        ExportProgress.ExportingRecipe(
                            recipeName = recipe.name,
                            current = index + 1,
                            total = recipes.size
                        )
                    )

                    try {
                        val originalHtml = recipeRepository.getOriginalHtml(recipe.id)
                        val files = recipeSerializer.serializeRecipe(recipe, originalHtml)
                        val prefix = files.folderName

                        // Write recipe.json
                        zipOut.putNextEntry(ZipEntry("$prefix/${RecipeSerializer.RECIPE_JSON_FILENAME}"))
                        zipOut.write(files.recipeJson.toByteArray(Charsets.UTF_8))
                        zipOut.closeEntry()

                        // Write original.html (if available)
                        if (files.originalHtml != null) {
                            zipOut.putNextEntry(ZipEntry("$prefix/${RecipeSerializer.RECIPE_HTML_FILENAME}"))
                            zipOut.write(files.originalHtml.toByteArray(Charsets.UTF_8))
                            zipOut.closeEntry()
                        }

                        // Write recipe.md
                        zipOut.putNextEntry(ZipEntry("$prefix/${RecipeSerializer.RECIPE_MARKDOWN_FILENAME}"))
                        zipOut.write(files.recipeMarkdown.toByteArray(Charsets.UTF_8))
                        zipOut.closeEntry()

                        // Write image file (if available)
                        if (recipe.imageUrl != null) {
                            val imageFile = imageDownloadService.getLocalImageFile(recipe.imageUrl)
                            if (imageFile != null) {
                                val imageName = "${RecipeSerializer.IMAGE_FILENAME_PREFIX}${imageFile.extension.let { ".$it" }}"
                                zipOut.putNextEntry(ZipEntry("$prefix/$imageName"))
                                imageFile.inputStream().use { it.copyTo(zipOut) }
                                zipOut.closeEntry()
                            }
                        }

                        exportedCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to export recipe: ${recipe.name}", e)
                        failedCount++
                    }
                }

                // Export meal plans grouped by date
                val mealPlans = mealPlanRepository.getAllMealPlansOnce()
                if (mealPlans.isNotEmpty()) {
                    val mealPlansByDate = mealPlans.groupBy { it.date }
                    for ((date, entries) in mealPlansByDate) {
                        try {
                            val mealPlanJson = json.encodeToString<List<MealPlanEntry>>(entries)
                            zipOut.putNextEntry(ZipEntry("$MEAL_PLANS_FOLDER/$date.json"))
                            zipOut.write(mealPlanJson.toByteArray(Charsets.UTF_8))
                            zipOut.closeEntry()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to export meal plans for date $date", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return ExportResult.Error("Failed to create ZIP file: ${e.message}")
        }

        val result = ExportResult.Success(exportedCount = exportedCount, failedCount = failedCount)
        onProgress(ExportProgress.Complete(result))
        return result
    }
}
