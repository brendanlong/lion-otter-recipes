package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.remote.PageFetchException
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeHtmlExtractor
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class ImportRecipeUseCase @Inject constructor(
    private val webScraperService: WebScraperService,
    private val parseHtmlUseCase: ParseHtmlUseCase
) {
    sealed class ImportResult {
        data class Success(val recipe: Recipe) : ImportResult()
        data class Error(val message: String, val retryable: Boolean = false) : ImportResult()
        object NoApiKey : ImportResult()
    }

    sealed class ImportProgress {
        object FetchingPage : ImportProgress()
        data class PageMetadataAvailable(val title: String?, val imageUrl: String?) : ImportProgress()
        object ParsingRecipe : ImportProgress()
        data class RecipeNameAvailable(val name: String) : ImportProgress()
        object SavingRecipe : ImportProgress()
        data class Complete(val result: ImportResult) : ImportProgress()
    }

    suspend fun execute(
        url: String,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        // Fetch page content
        onProgress(ImportProgress.FetchingPage)
        var effectiveUrl = url
        var pageResult = webScraperService.fetchPage(url)

        // If HTTP fails, try HTTPS as a fallback
        if (pageResult.isFailure && url.startsWith("http://")) {
            val httpsUrl = url.replaceFirst("http://", "https://")
            val httpsResult = webScraperService.fetchPage(httpsUrl)
            if (httpsResult.isSuccess) {
                pageResult = httpsResult
                effectiveUrl = httpsUrl
            }
        }

        if (pageResult.isFailure) {
            val error = pageResult.exceptionOrNull()
            Log.w(TAG, "Failed to fetch $url", error)
            return ImportResult.Error(
                message = error?.message ?: "Failed to fetch page",
                retryable = (error as? PageFetchException)?.isTransient == true
            )
        }
        val page = pageResult.getOrThrow()

        // Check for cancellation before expensive AI parsing
        coroutineContext.ensureActive()

        // Report page metadata (title + image) before expensive AI parsing
        onProgress(ImportProgress.PageMetadataAvailable(
            title = RecipeHtmlExtractor.extractTitle(page.originalHtml),
            imageUrl = page.imageUrl
        ))

        // Use ParseHtmlUseCase to parse the HTML content
        val parseResult = parseHtmlUseCase.parseHtml(
            html = page.originalHtml,
            sourceUrl = effectiveUrl,
            imageUrl = page.imageUrl,
            saveRecipe = true,
            onProgress = { progress ->
                when (progress) {
                    is ParseHtmlUseCase.ParseProgress.ExtractingContent -> {
                        // Already past fetching
                    }
                    is ParseHtmlUseCase.ParseProgress.ParsingRecipe -> {
                        onProgress(ImportProgress.ParsingRecipe)
                    }
                    is ParseHtmlUseCase.ParseProgress.RecipeNameAvailable -> {
                        onProgress(ImportProgress.RecipeNameAvailable(progress.name))
                    }
                    is ParseHtmlUseCase.ParseProgress.SavingRecipe -> {
                        onProgress(ImportProgress.SavingRecipe)
                    }
                    is ParseHtmlUseCase.ParseProgress.Complete -> {
                        // Will be handled by the return value
                    }
                }
            }
        )

        return when (parseResult) {
            is ParseHtmlUseCase.ParseResult.Success -> {
                onProgress(ImportProgress.Complete(ImportResult.Success(parseResult.recipe)))
                ImportResult.Success(parseResult.recipe)
            }
            is ParseHtmlUseCase.ParseResult.Error -> {
                ImportResult.Error(parseResult.message, parseResult.retryable)
            }
            ParseHtmlUseCase.ParseResult.NoApiKey -> {
                ImportResult.NoApiKey
            }
        }
    }

    companion object {
        private const val TAG = "ImportRecipeUseCase"
    }
}
