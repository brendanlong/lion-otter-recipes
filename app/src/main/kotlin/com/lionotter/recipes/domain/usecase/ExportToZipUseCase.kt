package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Use case for exporting recipes to a ZIP file.
 * Uses a standard folder structure (folder = recipe name plus a short ID, unique per export):
 * - <folder>/recipe.json
 * - <folder>/recipe.md
 * - <folder>/image.* (recipe image, if available)
 * - meal-plans/<date>.json
 */
class ExportToZipUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val recipeSerializer: RecipeSerializer,
    private val mealPlanRepository: MealPlanRepository,
    private val imageStore: ImageStore,
    private val json: Json
) {
    companion object {
        private const val TAG = "ExportToZipUseCase"
        const val MEAL_PLANS_FOLDER = "meal-plans"
    }

    data class ExportSummary(val exportedCount: Int, val failedCount: Int)

    sealed class ExportProgress {
        object Starting : ExportProgress()
        data class ExportingRecipe(
            val recipeName: String,
            val current: Int,
            val total: Int
        ) : ExportProgress()
    }

    /**
     * Export all recipes to a ZIP file written to the given output stream.
     */
    suspend fun exportAllRecipes(
        outputStream: OutputStream,
        onProgress: suspend (ExportProgress) -> Unit = {}
    ): Outcome<ExportSummary> {
        onProgress(ExportProgress.Starting)

        val recipes = recipeRepository.getAllRecipes().first()
        if (recipes.isEmpty()) {
            return Outcome.Failure(RecipeError.Archive(ArchiveFailure.NOTHING_TO_EXPORT))
        }

        var exportedCount = 0
        var failedCount = 0
        val usedFolderNames = mutableSetOf<String>()

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
                        val files = recipeSerializer.serializeRecipe(recipe)
                        var prefix = files.folderName
                        var suffix = 2
                        while (!usedFolderNames.add(prefix)) {
                            prefix = "${files.folderName} $suffix"
                            suffix++
                        }

                        // Write recipe.json
                        zipOut.putNextEntry(ZipEntry("$prefix/${RecipeSerializer.RECIPE_JSON_FILENAME}"))
                        zipOut.write(files.recipeJson.toByteArray(Charsets.UTF_8))
                        zipOut.closeEntry()

                        // Write recipe.md
                        zipOut.putNextEntry(ZipEntry("$prefix/${RecipeSerializer.RECIPE_MARKDOWN_FILENAME}"))
                        zipOut.write(files.recipeMarkdown.toByteArray(Charsets.UTF_8))
                        zipOut.closeEntry()

                        // Write image file (if available)
                        if (recipe.imageUrl != null) {
                            val imageFile = imageStore.localImageFile(recipe.imageUrl)
                            if (imageFile != null) {
                                val imageName = "${RecipeSerializer.IMAGE_FILENAME_PREFIX}${imageFile.extension.let { ".$it" }}"
                                zipOut.putNextEntry(ZipEntry("$prefix/$imageName"))
                                imageFile.inputStream().use { it.copyTo(zipOut) }
                                zipOut.closeEntry()
                            }
                        }

                        exportedCount++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ErrorLog.e(TAG, "Failed to export recipe: ${recipe.name}", e)
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
                            ErrorLog.e(TAG, "Failed to export meal plans for date $date", e)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to create ZIP file", e)
            return Outcome.Failure(RecipeError.Archive(ArchiveFailure.WRITE_FAILED))
        }

        return Outcome.Success(ExportSummary(exportedCount = exportedCount, failedCount = failedCount))
    }
}
