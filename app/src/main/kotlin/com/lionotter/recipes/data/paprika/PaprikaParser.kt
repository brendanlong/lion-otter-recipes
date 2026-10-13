package com.lionotter.recipes.data.paprika

import com.lionotter.recipes.domain.port.PaprikaExportReader
import com.lionotter.recipes.domain.port.PaprikaExportRecipe
import com.lionotter.recipes.domain.util.ArchiveBudget
import com.lionotter.recipes.domain.util.ArchiveTooLargeException
import com.lionotter.recipes.domain.util.BoundedInputStream
import com.lionotter.recipes.util.ErrorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

/** A recipe listed in a Paprika export, for the import selection list. */
@Serializable
data class PaprikaIndexEntry(val uid: String, val name: String)

/**
 * Parser for Paprika recipe export files (.paprikarecipes).
 *
 * The export format is a ZIP archive containing one gzip-compressed JSON file
 * (.paprikarecipe) per recipe. Entries can embed large base64 photos, so the export is
 * streamed one entry at a time with per-entry and total size limits; a malformed entry is
 * reported and skipped rather than failing the whole file.
 */
class PaprikaParser @Inject constructor(
    private val json: Json
) : PaprikaExportReader {
    companion object {
        private const val TAG = "PaprikaParser"
        private const val MAX_COMPRESSED_ENTRY_BYTES = 30L * 1024 * 1024
        private const val MAX_RECIPE_JSON_BYTES = 60L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
    }

    /**
     * Stream the recipes in a Paprika export. [onRecipe] is called for each parsed recipe
     * and [onError] for each entry that couldn't be read.
     *
     * @throws ArchiveTooLargeException if the archive exceeds the size limits
     */
    fun forEachRecipe(
        inputStream: InputStream,
        onError: (entryName: String, error: Exception) -> Unit = { _, _ -> },
        onRecipe: (PaprikaRecipe) -> Unit
    ) {
        forEachRecipeJson(inputStream, onError) { entryName, jsonString ->
            val recipe = try {
                decodeRecipe(jsonString)
            } catch (e: Exception) {
                onError(entryName, e)
                null
            }
            if (recipe != null) onRecipe(recipe)
        }
    }

    override fun readRecipes(
        inputStream: InputStream,
        onError: (entryName: String, error: Exception) -> Unit,
        onRecipe: (PaprikaExportRecipe) -> Unit
    ) {
        forEachRecipe(inputStream, onError) { recipe ->
            onRecipe(
                PaprikaExportRecipe(
                    uid = recipe.uid,
                    name = recipe.name,
                    sourceUrl = recipe.sourceUrl,
                    imageUrl = recipe.imageUrl,
                    photoData = recipe.photoData,
                    contentForAi = formatForAi(recipe)
                )
            )
        }
    }

    /** Read only the uid and name of every recipe (photos are not decoded). */
    fun parseIndex(inputStream: InputStream): List<PaprikaIndexEntry> {
        val results = mutableListOf<PaprikaIndexEntry>()
        forEachRecipeJson(
            inputStream,
            onError = { name, e -> ErrorLog.w(TAG, "Skipping unreadable Paprika entry $name", e) }
        ) { entryName, jsonString ->
            try {
                results.add(json.decodeFromString<PaprikaIndexEntry>(jsonString))
            } catch (e: Exception) {
                ErrorLog.w(TAG, "Skipping malformed Paprika entry $entryName", e)
            }
        }
        return results
    }

    /**
     * Parse a whole export into memory. Only suitable for small exports (tests, previews);
     * imports use [forEachRecipe].
     */
    fun parseExport(inputStream: InputStream): List<PaprikaRecipe> {
        val recipes = mutableListOf<PaprikaRecipe>()
        forEachRecipe(
            inputStream,
            onError = { name, e -> ErrorLog.w(TAG, "Skipping unreadable Paprika entry $name", e) }
        ) { recipes.add(it) }
        return recipes
    }

    fun decodeRecipe(jsonString: String): PaprikaRecipe = json.decodeFromString<PaprikaRecipe>(jsonString)

    private fun forEachRecipeJson(
        inputStream: InputStream,
        onError: (entryName: String, error: Exception) -> Unit,
        onJson: (entryName: String, json: String) -> Unit
    ) {
        val budget = ArchiveBudget(MAX_TOTAL_BYTES)
        ZipInputStream(inputStream).use { zipStream ->
            var entryCount = 0
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (++entryCount > MAX_ENTRIES) throw ArchiveTooLargeException("Export has too many entries")
                if (!entry.isDirectory && entry.name.endsWith(".paprikarecipe")) {
                    val jsonString = try {
                        readEntryJson(zipStream, budget)
                    } catch (e: ArchiveTooLargeException) {
                        if (budget.consumed > MAX_TOTAL_BYTES) throw e
                        onError(entry.name, e)
                        null
                    } catch (e: Exception) {
                        onError(entry.name, e)
                        null
                    }
                    if (jsonString != null) onJson(entry.name, jsonString)
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
    }

    private fun readEntryJson(zipStream: ZipInputStream, budget: ArchiveBudget): String {
        val compressed = BoundedInputStream(zipStream, MAX_COMPRESSED_ENTRY_BYTES).readBytes()
        return GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            BoundedInputStream(gzip, MAX_RECIPE_JSON_BYTES, budget).bufferedReader().readText()
        }
    }

    /**
     * Parse a single .paprikarecipe file (gzip-compressed JSON) from raw bytes.
     */
    fun parseRecipeEntry(compressedBytes: ByteArray): PaprikaRecipe {
        val jsonString = GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { gzipStream ->
            gzipStream.bufferedReader().readText()
        }
        return decodeRecipe(jsonString)
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
