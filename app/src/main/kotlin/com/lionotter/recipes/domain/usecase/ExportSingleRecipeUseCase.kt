package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.util.RecipeSerializer
import java.io.OutputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Use case for exporting a single recipe to a .lorecipes file: a ZIP containing the same
 * recipe folder as the bulk export (see [ExportToZipUseCase]).
 *
 * The .lorecipes extension allows the app to register as a handler for these files,
 * enabling recipe sharing between users.
 */
class ExportSingleRecipeUseCase @Inject constructor(
    private val recipeSerializer: RecipeSerializer,
    private val imageStore: ImageStore
) {
    /**
     * Export a single recipe to a .lorecipes file written to the given output stream.
     * Throws if writing fails.
     */
    suspend fun exportRecipe(recipe: Recipe, outputStream: OutputStream) {
        val imageFile = recipe.imageUrl?.let { imageStore.localImageFile(it) }
        ZipOutputStream(outputStream).use { zipOut ->
            recipeSerializer.writeRecipeFolder(zipOut, recipeSerializer.exportFolderName(recipe), recipe, imageFile)
        }
    }
}
