package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.util.ArchiveTooLargeException
import com.lionotter.recipes.domain.util.ZipImportHelper
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import javax.inject.Inject

/**
 * Use case for importing recipes from a ZIP / .lorecipes file.
 * Expects the same folder structure as the export:
 * - <folder>/recipe.json
 * - <folder>/recipe.md (ignored on import, regenerated from data)
 * - <folder>/image.* (recipe image, used if present)
 * - meal-plans/<date>.json (imported only for recipes that were imported or already exist)
 *
 * Skips recipes that already exist locally (by ID).
 */
class ImportFromZipUseCase @Inject constructor(
    private val zipImportHelper: ZipImportHelper
) {
    companion object {
        private const val TAG = "ImportFromZipUseCase"
    }

    sealed class ImportResult {
        data class Success(
            val importedCount: Int,
            val failedCount: Int,
            val skippedCount: Int,
            val mealPlansImported: Int = 0
        ) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    sealed class ImportProgress {
        object Starting : ImportProgress()
        object ReadingZip : ImportProgress()
        data class ImportingRecipe(
            val recipeName: String,
            val current: Int,
            /** Total recipes to import, or 0 if unknown (importing everything). */
            val total: Int
        ) : ImportProgress()
        data class Complete(val result: ImportResult) : ImportProgress()
    }

    /**
     * Import recipes from a ZIP file input stream.
     * @param selectedRecipeIds If non-null, only import recipes whose IDs are in this set.
     */
    suspend fun importFromZip(
        inputStream: InputStream,
        selectedRecipeIds: Set<String>? = null,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        onProgress(ImportProgress.Starting)
        onProgress(ImportProgress.ReadingZip)

        val summary = try {
            zipImportHelper.importFromZip(inputStream, selectedRecipeIds) { name, index ->
                onProgress(ImportProgress.ImportingRecipe(name, index, selectedRecipeIds?.size ?: 0))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArchiveTooLargeException) {
            ErrorLog.e(TAG, "ZIP import rejected: archive too large", e)
            return ImportResult.Error("This file is too large to import (${e.message}).")
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to read ZIP file", e)
            return ImportResult.Error("Couldn't read the file. Is it a Lion+Otter export? (${e.message})")
        }

        if (summary.importedCount + summary.skippedCount + summary.failedCount == 0) {
            return ImportResult.Error("No recipes found in file")
        }

        val result = ImportResult.Success(
            importedCount = summary.importedCount,
            failedCount = summary.failedCount,
            skippedCount = summary.skippedCount,
            mealPlansImported = summary.mealPlansImported
        )
        onProgress(ImportProgress.Complete(result))
        return result
    }
}
