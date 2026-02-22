package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.Recipe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    }

    /**
     * Files that represent a single recipe in the export folder structure.
     */
    data class RecipeFiles(
        val folderName: String,
        val recipeJson: String,
        val recipeMarkdown: String
    )

    /**
     * Serialize a recipe into the standard export files.
     */
    fun serializeRecipe(recipe: Recipe): RecipeFiles {
        return RecipeFiles(
            folderName = sanitizeFolderName(recipe.name),
            recipeJson = json.encodeToString(recipe),
            recipeMarkdown = RecipeMarkdownFormatter.format(recipe)
        )
    }

    /**
     * Deserialize a recipe from its JSON representation.
     */
    fun deserializeRecipe(jsonContent: String): Recipe {
        return json.decodeFromString<Recipe>(jsonContent)
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
