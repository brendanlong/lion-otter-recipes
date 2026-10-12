package com.lionotter.recipes.domain.util

import android.util.Log
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/** A recipe found in a .lorecipes / ZIP backup, for the import selection list. */
data class ZipRecipeIndexEntry(val id: String, val name: String)

/**
 * Streams recipes and meal plans out of ZIP backups (.lorecipes / exported ZIPs).
 *
 * Archives are processed one entry at a time — images are written straight to disk and only
 * one recipe is held in memory — with per-entry and total size limits to guard against zip bombs.
 * Expected layout: `<folder>/recipe.json`, `<folder>/recipe.md`, `<folder>/image.*` and
 * `meal-plans/<date>.json`. Folder names are not significant (older exports used the recipe name,
 * newer ones append a short ID to keep them unique).
 */
class ZipImportHelper @Inject constructor(
    private val recipeRepository: IRecipeRepository,
    private val recipeSerializer: RecipeSerializer,
    private val mealPlanRepository: IMealPlanRepository,
    private val json: Json,
    private val imageDownloadService: ImageDownloadService
) {
    companion object {
        private const val TAG = "ZipImportHelper"
        const val MEAL_PLANS_FOLDER = "meal-plans"
        private const val MAX_TEXT_ENTRY_BYTES = 2L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 50_000
    }

    /**
     * Result of importing a single recipe from a ZIP folder.
     */
    sealed class SingleRecipeResult {
        data class Imported(val recipeId: String) : SingleRecipeResult()
        data class Skipped(val recipeId: String) : SingleRecipeResult()
        object LimitReached : SingleRecipeResult()
        data class Failed(val error: Exception) : SingleRecipeResult()
    }

    data class ImportSummary(
        val importedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val mealPlansImported: Int
    )

    /**
     * Read just the recipe IDs and names from a ZIP, without loading images. Malformed recipe
     * entries are skipped (and logged).
     */
    fun readRecipeIndex(inputStream: InputStream): List<ZipRecipeIndexEntry> {
        val results = mutableListOf<ZipRecipeIndexEntry>()
        val budget = ArchiveBudget(MAX_TOTAL_BYTES)
        forEachEntry(inputStream) { zipIn, folder, fileName ->
            if (folder != MEAL_PLANS_FOLDER && fileName == RecipeSerializer.RECIPE_JSON_FILENAME) {
                try {
                    val recipe = recipeSerializer.deserializeRecipe(readText(zipIn, budget))
                    results.add(ZipRecipeIndexEntry(recipe.id, recipe.name))
                } catch (e: ArchiveTooLargeException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable recipe in $folder", e)
                }
            }
        }
        return results
    }

    /**
     * Import recipes (optionally only [selectedRecipeIds]) and the meal plans that reference
     * imported or already-present recipes.
     *
     * @param onRecipe called before each selected recipe is imported, with its name and 1-based index
     */
    suspend fun importFromZip(
        inputStream: InputStream,
        selectedRecipeIds: Set<String>?,
        onRecipe: suspend (name: String, index: Int) -> Unit = { _, _ -> }
    ): ImportSummary {
        val budget = ArchiveBudget(MAX_TOTAL_BYTES)
        var imported = 0
        var skipped = 0
        var failed = 0
        var index = 0
        val presentRecipeIds = mutableSetOf<String>()
        val mealPlanEntries = mutableListOf<MealPlanEntry>()

        var currentFolder: String? = null
        var folderRecipe: Recipe? = null
        var folderImage: String? = null
        var folderHadBadJson = false

        suspend fun flushFolder() {
            val recipe = folderRecipe
            if (recipe != null && (selectedRecipeIds == null || recipe.id in selectedRecipeIds) &&
                recipe.id !in presentRecipeIds
            ) {
                index++
                onRecipe(recipe.name, index)
                when (val result = importRecipe(recipe, folderImage)) {
                    is SingleRecipeResult.Imported -> {
                        imported++
                        presentRecipeIds.add(recipe.id)
                        folderImage = null // now owned by the recipe
                    }
                    is SingleRecipeResult.Skipped -> {
                        skipped++
                        presentRecipeIds.add(recipe.id)
                    }
                    SingleRecipeResult.LimitReached -> failed++
                    is SingleRecipeResult.Failed -> failed++
                }
            } else if (recipe == null && folderHadBadJson && selectedRecipeIds == null) {
                failed++
            }
            folderImage?.let { imageDownloadService.deleteLocalImage(it) }
            folderRecipe = null
            folderImage = null
            folderHadBadJson = false
        }

        forEachEntrySuspend(inputStream) { zipIn, folder, fileName ->
            coroutineContext.ensureActive()
            if (folder == MEAL_PLANS_FOLDER) {
                if (fileName.endsWith(".json")) {
                    try {
                        mealPlanEntries += json.decodeFromString<List<MealPlanEntry>>(readText(zipIn, budget))
                    } catch (e: ArchiveTooLargeException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read meal plan file $fileName", e)
                    }
                }
                return@forEachEntrySuspend
            }
            if (folder != currentFolder) {
                flushFolder()
                currentFolder = folder
            }
            when {
                fileName == RecipeSerializer.RECIPE_JSON_FILENAME -> try {
                    folderRecipe = recipeSerializer.deserializeRecipe(readText(zipIn, budget))
                } catch (e: ArchiveTooLargeException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read recipe in folder $folder", e)
                    folderHadBadJson = true
                }
                isImageFile(fileName) && folderImage == null -> {
                    val known = folderRecipe
                    val wanted = known == null || selectedRecipeIds == null || known.id in selectedRecipeIds
                    if (wanted) {
                        val extension = ".${fileName.substringAfterLast('.', "jpg")}"
                        folderImage = imageDownloadService.saveImageFromStream(
                            BoundedInputStream(zipIn, ResourceLimits.MAX_IMAGE_SIZE_BYTES, budget),
                            extension
                        )
                    }
                }
            }
        }
        flushFolder()

        val mealPlansImported = importMealPlans(mealPlanEntries.filter { it.recipeId in presentRecipeIds })
        return ImportSummary(imported, skipped, failed, mealPlansImported)
    }

    /**
     * Imports a single recipe, skipping it if a recipe with the same ID already exists.
     *
     * @param bundledImageUri local file:// URI of the image bundled in the ZIP, if any
     */
    suspend fun importRecipe(recipe: Recipe, bundledImageUri: String?): SingleRecipeResult {
        return try {
            if (recipeRepository.getRecipeByIdOnce(recipe.id) != null) {
                return SingleRecipeResult.Skipped(recipe.id)
            }

            val count = recipeRepository.getRecipeCount()
            if (count >= ResourceLimits.MAX_RECIPES) {
                Log.w(TAG, "Recipe limit reached ($count/${ResourceLimits.MAX_RECIPES})")
                return SingleRecipeResult.LimitReached
            }

            val localImageUrl = bundledImageUri
                ?: recipe.sourceImageUrl?.takeIf { it.isNotBlank() }?.let { imageDownloadService.downloadAndStore(it) }
                ?: imageDownloadService.downloadImageIfNeeded(recipe.imageUrl)

            withContext(NonCancellable) {
                val storedImageUrl = localImageUrl?.let { imageDownloadService.storeImage(it) }
                recipeRepository.saveRecipe(
                    recipe.copy(updatedAt = Clock.System.now(), imageUrl = storedImageUrl)
                )
            }
            SingleRecipeResult.Imported(recipe.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to import recipe ${recipe.id} (${recipe.name})", e)
            SingleRecipeResult.Failed(e)
        }
    }

    private suspend fun importMealPlans(entries: List<MealPlanEntry>): Int {
        var imported = 0
        withContext(NonCancellable) {
            for (entry in entries) {
                try {
                    mealPlanRepository.saveMealPlan(entry)
                    imported++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import meal plan entry ${entry.id}", e)
                }
            }
        }
        return imported
    }

    private fun readText(zipIn: ZipInputStream, budget: ArchiveBudget): String =
        BoundedInputStream(zipIn, MAX_TEXT_ENTRY_BYTES, budget).readBytes().toString(Charsets.UTF_8)

    private inline fun forEachEntry(
        inputStream: InputStream,
        block: (ZipInputStream, folder: String, fileName: String) -> Unit
    ) {
        ZipInputStream(inputStream).use { zipIn ->
            var entryCount = 0
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (++entryCount > MAX_ENTRIES) throw ArchiveTooLargeException("Archive has too many entries")
                if (!entry.isDirectory) {
                    val parts = entry.name.split("/", limit = 2)
                    if (parts.size == 2 && parts[1].isNotEmpty()) block(zipIn, parts[0], parts[1])
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    private suspend inline fun forEachEntrySuspend(
        inputStream: InputStream,
        crossinline block: suspend (ZipInputStream, folder: String, fileName: String) -> Unit
    ) {
        ZipInputStream(inputStream).use { zipIn ->
            var entryCount = 0
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (++entryCount > MAX_ENTRIES) throw ArchiveTooLargeException("Archive has too many entries")
                if (!entry.isDirectory) {
                    val parts = entry.name.split("/", limit = 2)
                    if (parts.size == 2 && parts[1].isNotEmpty()) block(zipIn, parts[0], parts[1])
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return RecipeSerializer.IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }
}
