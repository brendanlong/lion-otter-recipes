package com.lionotter.recipes.domain.util

import android.util.Log
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Shared helper for importing recipes and meal plans from ZIP files.
 *
 * Consolidates the common logic used by ImportFromZipUseCase and ImportSelectionViewModel:
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
     * Contents read from a ZIP file, with text files and binary image data separated.
     */
    data class ZipContents(
        /** Map of folder name to (filename to text content) for text files */
        val textFiles: Map<String, Map<String, String>>,
        /** Map of folder name to (filename to raw bytes) for image files */
        val imageFiles: Map<String, Map<String, ByteArray>>
    )

    /**
     * Reads all entries from a ZIP input stream into a folder-grouped structure.
     * Text files (json, html, md) are stored as strings.
     * Image files are stored as raw bytes for later saving.
     *
     * @return [ZipContents] with text and image files, or null if reading fails
     */
    fun readZipContents(inputStream: InputStream): ZipContents? {
        val textContents = mutableMapOf<String, MutableMap<String, String>>()
        val imageContents = mutableMapOf<String, MutableMap<String, ByteArray>>()

        try {
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val pathParts = entry.name.split("/", limit = 2)
                        if (pathParts.size == 2) {
                            val folderName = pathParts[0]
                            val fileName = pathParts[1]
                            val isImage = isImageFile(fileName)

                            if (isImage) {
                                val bytes = zipIn.readBytes()
                                imageContents.getOrPut(folderName) { mutableMapOf() }[fileName] = bytes
                            } else {
                                val content = zipIn.readBytes().toString(Charsets.UTF_8)
                                textContents.getOrPut(folderName) { mutableMapOf() }[fileName] = content
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ZIP contents", e)
            return null
        }

        if (textContents.isEmpty() && imageContents.isEmpty()) return null
        return ZipContents(textContents, imageContents)
    }

    /**
     * Imports a single recipe from its folder files.
     *
     * Handles deserialization, duplicate checking (by ID), image importing
     * (from bundled image or download), and saving to the repository.
     *
     * @param files map of filename to content for a single recipe folder (text files)
     * @param imageFiles map of filename to bytes for image files in the folder (may be empty)
     * @return the result of the import attempt
     */
    suspend fun importRecipe(
        files: Map<String, String>,
        imageFiles: Map<String, ByteArray> = emptyMap()
    ): SingleRecipeResult {
        val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME]
            ?: return SingleRecipeResult.NoJson

        return try {
            val recipe = recipeSerializer.deserializeRecipe(jsonContent)

            val existing = recipeRepository.getRecipeByIdOnce(recipe.id)
            if (existing != null) {
                return SingleRecipeResult.Skipped(recipe.id)
            }

            // Try to use bundled image from the ZIP first, then fall back to download
            val localImageUrl = importImageFromZipOrDownload(imageFiles, recipe.imageUrl, recipe.sourceImageUrl)

            val importedRecipe = recipe.copy(
                updatedAt = Clock.System.now(),
                imageUrl = localImageUrl
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

    /**
     * Tries to import an image from bundled ZIP data, falling back to downloading
     * from the source image URL or the stored image URL.
     */
    private suspend fun importImageFromZipOrDownload(
        imageFiles: Map<String, ByteArray>,
        imageUrl: String?,
        sourceImageUrl: String?
    ): String? {
        // 1. Try bundled image from the ZIP export
        val bundledImage = imageFiles.entries.firstOrNull { isImageFile(it.key) }
        if (bundledImage != null) {
            val extension = ".${bundledImage.key.substringAfterLast('.', "jpg")}"
            val localUri = imageDownloadService.saveImageFromStream(
                ByteArrayInputStream(bundledImage.value),
                extension
            )
            if (localUri != null) return localUri
        }

        // 2. Try downloading from the source image URL (original remote URL)
        if (!sourceImageUrl.isNullOrBlank()) {
            val localUri = imageDownloadService.downloadAndStore(sourceImageUrl)
            if (localUri != null) return localUri
        }

        // 3. Fall back to the stored imageUrl (may be a file:// URI that doesn't exist,
        //    or a remote URL that can be downloaded)
        return imageDownloadService.downloadImageIfNeeded(imageUrl)
    }

    private fun isImageFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return RecipeSerializer.IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }
}
