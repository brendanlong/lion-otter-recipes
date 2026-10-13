package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.domain.port.FetchedPage
import com.lionotter.recipes.domain.port.PageContentExtractor
import com.lionotter.recipes.domain.port.PageFetcher
import com.lionotter.recipes.domain.port.ParseAttachment
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
            onProgress = { reportParseProgress(it, onProgress) }
        )
    }

    /**
     * Import the recipe in a file's [content]: text is sent to the AI as is, HTML is prepared
     * like a downloaded page. The recipe's source URL is the page's canonical address if an
     * HTML file states one, otherwise there is none.
     */
    suspend fun importFile(
        content: String,
        format: RecipeFileFormat,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): Outcome<Recipe> {
        if (content.isBlank()) return Outcome.Failure(RecipeError.ImportFile(ImportFileFailure.EMPTY))
        return when (format) {
            RecipeFileFormat.TEXT -> parseHtmlUseCase.parseText(
                text = content,
                saveRecipe = true,
                onProgress = { reportParseProgress(it, onProgress) }
            )
            RecipeFileFormat.HTML -> {
                val imageUrl = pageContentExtractor.extractImageUrl(content)
                onProgress(ImportProgress.PageMetadataAvailable(pageContentExtractor.extractTitle(content), imageUrl))
                parseHtmlUseCase.parseHtml(
                    html = content,
                    sourceUrl = pageContentExtractor.extractCanonicalUrl(content),
                    imageUrl = imageUrl,
                    saveRecipe = true,
                    onProgress = { reportParseProgress(it, onProgress) }
                )
            }
            RecipeFileFormat.IMAGE, RecipeFileFormat.PDF ->
                throw IllegalArgumentException("$format files are imported with importAttachments")
        }
    }

    /**
     * Import the recipe in photos (in page order; the first becomes the recipe's image) or a PDF
     * (which gives no image). The recipe has no source URL.
     */
    suspend fun importAttachments(
        attachments: List<ParseAttachment>,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): Outcome<Recipe> {
        if (attachments.isEmpty()) return Outcome.Failure(RecipeError.ImportFile(ImportFileFailure.EMPTY))
        return parseHtmlUseCase.parseAttachments(
            attachments = attachments,
            recipePhoto = attachments.first() as? ParseAttachment.Image,
            onProgress = { reportParseProgress(it, onProgress) }
        )
    }

    private suspend fun reportParseProgress(
        progress: ParseHtmlUseCase.ParseProgress,
        onProgress: suspend (ImportProgress) -> Unit
    ) {
        when (progress) {
            // Content is extracted right before parsing; there's nothing to show for it
            ParseHtmlUseCase.ParseProgress.ExtractingContent -> Unit
            ParseHtmlUseCase.ParseProgress.ParsingRecipe -> onProgress(ImportProgress.ParsingRecipe)
            is ParseHtmlUseCase.ParseProgress.RecipeNameAvailable ->
                onProgress(ImportProgress.RecipeNameAvailable(progress.name))
            ParseHtmlUseCase.ParseProgress.SavingRecipe -> onProgress(ImportProgress.SavingRecipe)
        }
    }
}
