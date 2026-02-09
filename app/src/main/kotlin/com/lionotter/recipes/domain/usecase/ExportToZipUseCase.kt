package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.util.RecipeSerializer
import kotlinx.coroutines.flow.first
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Use case for exporting recipes to a ZIP file.
 * Uses the same folder structure as Google Drive export:
 * - recipe-name/recipe.json
 * - recipe-name/original.html (if available)
 * - recipe-name/recipe.md
 */
class ExportToZipUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val recipeSerializer: RecipeSerializer
) {
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

                        exportedCount++
                    } catch (e: Exception) {
                        failedCount++
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
