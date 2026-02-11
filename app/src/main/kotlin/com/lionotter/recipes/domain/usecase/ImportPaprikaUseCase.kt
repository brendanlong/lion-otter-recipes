package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.data.paprika.PaprikaRecipe
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Use case for importing recipes from a Paprika export file (.paprikarecipes).
 *
 * For each recipe in the export:
 * 1. Parse the Paprika recipe data
 * 2. Send the ingredients and directions (not the source URL) to the AI for structured parsing
 * 3. Use the Paprika image if available (base64-decoded photo_data), otherwise try to fetch from source URL
 * 4. Save the resulting recipe to the database
 */
class ImportPaprikaUseCase @Inject constructor(
    private val paprikaParser: PaprikaParser,
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val webScraperService: WebScraperService
) {
    sealed class ImportResult {
        data class Success(
            val importedCount: Int,
            val failedCount: Int,
            val failedRecipes: List<String> = emptyList()
        ) : ImportResult()

        data class Cancelled(
            val importedCount: Int,
            val failedCount: Int
        ) : ImportResult()

        data class Error(val message: String) : ImportResult()
        object NoApiKey : ImportResult()
    }

    sealed class ImportProgress {
        object Parsing : ImportProgress()
        data class ImportingRecipe(
            val recipeName: String,
            val current: Int,
            val total: Int,
            val importedSoFar: Int = 0,
            val failedSoFar: Int = 0
        ) : ImportProgress()

        data class Complete(val result: ImportResult) : ImportProgress()
    }

    /**
     * Import recipes from a Paprika export file.
     *
     * @param inputStream InputStream of the .paprikarecipes file
     * @param selectedRecipeNames If non-null, only import recipes whose names are in this set
     * @param onProgress Callback for progress updates
     */
    suspend fun execute(
        inputStream: InputStream,
        selectedRecipeNames: Set<String>? = null,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        // Parse the export file
        onProgress(ImportProgress.Parsing)
        val allPaprikaRecipes = try {
            paprikaParser.parseExport(inputStream)
        } catch (e: Exception) {
            return ImportResult.Error("Failed to parse Paprika export: ${e.message}")
        }

        // Filter to selected recipes if a selection was provided
        val paprikaRecipes = if (selectedRecipeNames != null) {
            allPaprikaRecipes.filter { it.name in selectedRecipeNames }
        } else {
            allPaprikaRecipes
        }

        if (paprikaRecipes.isEmpty()) {
            return ImportResult.Error("No recipes found in export file")
        }

        var importedCount = 0
        var failedCount = 0
        val failedRecipes = mutableListOf<String>()

        try {
            paprikaRecipes.forEachIndexed { index, paprikaRecipe ->
                coroutineContext.ensureActive()

                onProgress(
                    ImportProgress.ImportingRecipe(
                        recipeName = paprikaRecipe.name,
                        current = index + 1,
                        total = paprikaRecipes.size,
                        importedSoFar = importedCount,
                        failedSoFar = failedCount
                    )
                )

                val result = importSingleRecipe(paprikaRecipe)
                if (result != null) {
                    importedCount++
                } else {
                    failedCount++
                    failedRecipes.add(paprikaRecipe.name)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val result = ImportResult.Cancelled(
                importedCount = importedCount,
                failedCount = failedCount
            )
            onProgress(ImportProgress.Complete(result))
            throw e
        }

        val result = ImportResult.Success(
            importedCount = importedCount,
            failedCount = failedCount,
            failedRecipes = failedRecipes
        )
        onProgress(ImportProgress.Complete(result))
        return result
    }

    private suspend fun importSingleRecipe(
        paprikaRecipe: PaprikaRecipe
    ): Recipe? {
        return try {
            // Format the Paprika recipe content for AI parsing
            val contentForAi = paprikaParser.formatForAi(paprikaRecipe)

            // Determine image URL:
            // 1. If Paprika has photo_data, we don't have a way to store raw image bytes in our model
            //    (we only store URLs), so try the image_url from Paprika first
            // 2. Fall back to fetching from source URL to get og:image
            val imageUrl = resolveImageUrl(paprikaRecipe)

            val sourceUrl = paprikaRecipe.sourceUrl?.takeIf { it.isNotBlank() }

            // Parse with AI via shared use case
            val parseResult = parseHtmlUseCase.parseText(
                text = contentForAi,
                sourceUrl = sourceUrl,
                imageUrl = imageUrl,
                saveRecipe = true
            )

            when (parseResult) {
                is ParseHtmlUseCase.ParseResult.Success -> parseResult.recipe
                is ParseHtmlUseCase.ParseResult.Error -> null
                ParseHtmlUseCase.ParseResult.NoApiKey -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolve the image URL for a Paprika recipe.
     *
     * Priority:
     * 1. Paprika's image_url field (direct URL to the image)
     * 2. Fetch the source page and extract og:image
     * 3. null if no image available
     */
    private suspend fun resolveImageUrl(paprikaRecipe: PaprikaRecipe): String? {
        // Try Paprika's image_url first
        if (!paprikaRecipe.imageUrl.isNullOrBlank()) {
            return paprikaRecipe.imageUrl
        }

        // Try fetching from source URL to get og:image
        if (!paprikaRecipe.sourceUrl.isNullOrBlank()) {
            return try {
                val pageResult = webScraperService.fetchPage(paprikaRecipe.sourceUrl)
                pageResult.getOrNull()?.imageUrl
            } catch (e: Exception) {
                null
            }
        }

        return null
    }
}
