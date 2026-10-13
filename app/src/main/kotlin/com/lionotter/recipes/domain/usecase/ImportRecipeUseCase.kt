package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.FetchedPage
import com.lionotter.recipes.domain.port.PageContentExtractor
import com.lionotter.recipes.domain.port.PageFetcher
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class ImportRecipeUseCase @Inject constructor(
    private val pageFetcher: PageFetcher,
    private val pageContentExtractor: PageContentExtractor,
    private val parseHtmlUseCase: ParseHtmlUseCase
) {
    sealed class ImportProgress {
        object FetchingPage : ImportProgress()
        data class PageMetadataAvailable(val title: String?, val imageUrl: String?) : ImportProgress()
        object ParsingRecipe : ImportProgress()
        data class RecipeNameAvailable(val name: String) : ImportProgress()
        object SavingRecipe : ImportProgress()
    }

    /**
     * @param url the page's address; for a page captured in the in-app browser, the address it
     *   ended up at (after redirects)
     * @param pageHtml the page as the in-app browser rendered it; null to download [url]
     */
    suspend fun execute(
        url: String,
        pageHtml: String? = null,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): Outcome<Recipe> {
        var effectiveUrl = url
        val page = if (pageHtml != null) {
            FetchedPage(html = pageHtml, imageUrl = pageContentExtractor.extractImageUrl(pageHtml))
        } else {
            onProgress(ImportProgress.FetchingPage)
            var pageResult = pageFetcher.fetchPage(url)

            // If HTTP fails, try HTTPS as a fallback
            if (pageResult is Outcome.Failure && url.startsWith("http://")) {
                val httpsUrl = url.replaceFirst("http://", "https://")
                val httpsResult = pageFetcher.fetchPage(httpsUrl)
                if (httpsResult is Outcome.Success) {
                    pageResult = httpsResult
                    effectiveUrl = httpsUrl
                }
            }

            when (pageResult) {
                is Outcome.Success -> pageResult.value
                is Outcome.Failure -> return pageResult
            }
        }

        // Check for cancellation before expensive AI parsing
        coroutineContext.ensureActive()

        // Report page metadata (title + image) before expensive AI parsing
        onProgress(ImportProgress.PageMetadataAvailable(
            title = pageContentExtractor.extractTitle(page.html),
            imageUrl = page.imageUrl
        ))

        return parseHtmlUseCase.parseHtml(
            html = page.html,
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
}
