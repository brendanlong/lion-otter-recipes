package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.Recipe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Shared logic for serializing and deserializing recipes in the standard
 * folder export format. Each recipe is represented as a folder containing:
 * - recipe.json: The structured recipe data
 * - recipe.md: A human-readable Markdown version
 * - image.* (jpg/png/webp/gif): The recipe image (if available)
 *
 * This is used by ZIP export/import to ensure a consistent format.
 */
class RecipeSerializer @Inject constructor(
    private val json: Json
) {
    companion object {
        const val RECIPE_JSON_FILENAME = "recipe.json"
        const val RECIPE_MARKDOWN_FILENAME = "recipe.md"
        const val IMAGE_FILENAME_PREFIX = "image"
        val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
        const val MEAL_PLANS_FOLDER = "meal-plans"
    }

    /** Serialize a recipe to its recipe.json content. */
    fun serializeRecipe(recipe: Recipe): String = json.encodeToString(recipe)

    /**
     * Write [recipe] into [zipOut] as `<folder>/recipe.json`, `<folder>/recipe.md` and, if
     * [imageFile] is given, `<folder>/image.<ext>`.
     */
    fun writeRecipeFolder(zipOut: ZipOutputStream, folder: String, recipe: Recipe, imageFile: File?) {
        fun writeEntry(name: String, write: () -> Unit) {
            zipOut.putNextEntry(ZipEntry("$folder/$name"))
            write()
            zipOut.closeEntry()
        }
        writeEntry(RECIPE_JSON_FILENAME) { zipOut.write(serializeRecipe(recipe).toByteArray(Charsets.UTF_8)) }
        writeEntry(RECIPE_MARKDOWN_FILENAME) {
            zipOut.write(RecipeMarkdownFormatter.format(recipe).toByteArray(Charsets.UTF_8))
        }
        if (imageFile != null) {
            writeEntry("$IMAGE_FILENAME_PREFIX.${imageFile.extension}") {
                imageFile.inputStream().use { it.copyTo(zipOut) }
            }
        }
    }

    /**
     * Deserialize a recipe from its JSON representation.
     */
    fun deserializeRecipe(jsonContent: String): Recipe {
        return json.decodeFromString<Recipe>(jsonContent)
    }

    /**
     * Folder name for a recipe inside an export. Includes a short ID suffix so recipes with the
     * same name (e.g. a "save as copy") don't collide; importers ignore folder names.
     */
    fun exportFolderName(recipe: Recipe): String {
        val shortId = recipe.id.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "recipe" }
        return "${sanitizeFolderName(recipe.name).take(90)} ($shortId)"
    }

    /**
     * Sanitize a recipe name for use as a folder/directory name.
     * Replaces invalid characters and normalizes whitespace.
     */
    fun sanitizeFolderName(name: String): String {
        return name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100)
            .ifEmpty { "Untitled Recipe" }
    }
}
