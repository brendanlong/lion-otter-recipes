package com.lionotter.recipes.data.paprika

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Parser for Paprika recipe export files (.paprikarecipes).
 *
 * The export format is a ZIP archive containing one gzip-compressed JSON file
 * (.paprikarecipe) per recipe.
 *
 * Parsing steps:
 * 1. Unzip the .paprikarecipes file to get individual .paprikarecipe files
 * 2. Gzip-decompress each .paprikarecipe file
 * 3. Parse the result as JSON
 */
class PaprikaParser @Inject constructor(
    private val json: Json
) {
    /**
     * Parse a .paprikarecipes ZIP file from an InputStream.
     * Returns a list of parsed recipes.
     */
    fun parseExport(inputStream: InputStream): List<PaprikaRecipe> {
        val recipes = mutableListOf<PaprikaRecipe>()
        ZipInputStream(inputStream).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".paprikarecipe")) {
                    val compressedBytes = zipStream.readBytes()
                    val recipe = parseRecipeEntry(compressedBytes)
                    recipes.add(recipe)
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
        return recipes
    }

    /**
     * Parse a single .paprikarecipe file (gzip-compressed JSON) from raw bytes.
     */
    fun parseRecipeEntry(compressedBytes: ByteArray): PaprikaRecipe {
        val jsonString = GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { gzipStream ->
            gzipStream.bufferedReader().readText()
        }
        return json.decodeFromString<PaprikaRecipe>(jsonString)
    }

    /**
     * Format a PaprikaRecipe into text content suitable for sending to the AI
     * for structured recipe parsing. Includes all useful metadata.
     */
    fun formatForAi(recipe: PaprikaRecipe): String = buildString {
        appendLine("Recipe: ${recipe.name}")
        appendLine()

        if (!recipe.description.isNullOrBlank()) {
            appendLine("Description: ${recipe.description}")
            appendLine()
        }

        if (!recipe.servings.isNullOrBlank()) {
            appendLine("Servings: ${recipe.servings}")
        }
        if (!recipe.prepTime.isNullOrBlank()) {
            appendLine("Prep Time: ${recipe.prepTime}")
        }
        if (!recipe.cookTime.isNullOrBlank()) {
            appendLine("Cook Time: ${recipe.cookTime}")
        }
        if (!recipe.totalTime.isNullOrBlank()) {
            appendLine("Total Time: ${recipe.totalTime}")
        }
        if (!recipe.difficulty.isNullOrBlank()) {
            appendLine("Difficulty: ${recipe.difficulty}")
        }

        appendLine()
        appendLine("Ingredients:")
        recipe.ingredients?.lines()?.filter { it.isNotBlank() }?.forEach { ingredient ->
            appendLine("- $ingredient")
        }

        appendLine()
        appendLine("Directions:")
        appendLine(recipe.directions.orEmpty())

        if (!recipe.notes.isNullOrBlank()) {
            appendLine()
            appendLine("Notes:")
            appendLine(recipe.notes)
        }

        if (!recipe.nutritionalInfo.isNullOrBlank()) {
            appendLine()
            appendLine("Nutritional Info:")
            appendLine(recipe.nutritionalInfo)
        }

        if (recipe.categories.isNotEmpty()) {
            appendLine()
            appendLine("Categories: ${recipe.categories.joinToString(", ")}")
        }

        if (!recipe.source.isNullOrBlank()) {
            appendLine()
            appendLine("Source: ${recipe.source}")
        }
    }
}
