package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeSerializer
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Use case for exporting a single recipe to a .lorecipes file.
 * The format is a ZIP file containing the same folder structure as the bulk export:
 * - recipe-name/recipe.json
 * - recipe-name/original.html (if available)
 * - recipe-name/recipe.md
 *
 * The .lorecipes extension allows the app to register as a handler for these files,
 * enabling recipe sharing between users.
 */
class ExportSingleRecipeUseCase @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val recipeSerializer: RecipeSerializer
) {
    sealed class ExportResult {
        data class Success(val fileName: String) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    /**
     * Export a single recipe to a .lorecipes file written to the given output stream.
     */
    suspend fun exportRecipe(
        recipe: Recipe,
        outputStream: OutputStream
    ): ExportResult {
        return try {
            val originalHtml = recipeRepository.getOriginalHtml(recipe.id)
            val files = recipeSerializer.serializeRecipe(recipe, originalHtml)
            val prefix = files.folderName

            ZipOutputStream(outputStream).use { zipOut ->
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
            }

            val fileName = "${recipeSerializer.sanitizeFolderName(recipe.name)}.lorecipes"
            ExportResult.Success(fileName)
        } catch (e: Exception) {
            ExportResult.Error("Failed to export recipe: ${e.message}")
        }
    }
}
