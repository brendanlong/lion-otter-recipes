package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.toRecipeError
import com.lionotter.recipes.domain.util.RecipeHtmlExtractor
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class ImportRecipeUseCase @Inject constructor(
    private val webScraperService: WebScraperService,
    private val parseHtmlUseCase: ParseHtmlUseCase
) {
    sealed class ImportProgress {
        object FetchingPage : ImportProgress()
        data class PageMetadataAvailable(val title: String?, val imageUrl: String?) : ImportProgress()
        object ParsingRecipe : ImportProgress()
        data class RecipeNameAvailable(val name: String) : ImportProgress()
        object SavingRecipe : ImportProgress()
    }

    suspend fun execute(
        url: String,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): Outcome<Recipe> {
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
            return Outcome.Failure(error.toRecipeError())
        }
        val page = pageResult.getOrThrow()

        // Check for cancellation before expensive AI parsing
        coroutineContext.ensureActive()

        // Report page metadata (title + image) before expensive AI parsing
        onProgress(ImportProgress.PageMetadataAvailable(
            title = RecipeHtmlExtractor.extractTitle(page.originalHtml),
            imageUrl = page.imageUrl
        ))

        return parseHtmlUseCase.parseHtml(
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
                }
            }
        )
    }

    companion object {
        private const val TAG = "ImportRecipeUseCase"
    }
}
