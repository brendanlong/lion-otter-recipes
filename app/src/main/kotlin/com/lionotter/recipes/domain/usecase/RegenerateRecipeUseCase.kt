package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.PageFetchException
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import javax.inject.Inject

class RegenerateRecipeUseCase @Inject constructor(
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val recipeRepository: IRecipeRepository,
    private val webScraperService: WebScraperService,
    private val imageDownloadService: ImageDownloadService
) {
    sealed class RegenerateResult {
        data class Success(val recipe: Recipe) : RegenerateResult()
        data class Error(val message: String, val retryable: Boolean = false) : RegenerateResult()
        object NoApiKey : RegenerateResult()
        object NoSourceUrl : RegenerateResult()
    }

    sealed class RegenerateProgress {
        object FetchingFromUrl : RegenerateProgress()
        object ParsingRecipe : RegenerateProgress()
        data class RecipeNameAvailable(val name: String) : RegenerateProgress()
        object SavingRecipe : RegenerateProgress()
        data class Complete(val result: RegenerateResult) : RegenerateProgress()
    }

    /**
     * Regenerate a recipe by re-fetching its source URL and re-parsing with AI.
     * Requires the recipe to have a source URL.
     * Preserves the recipe ID, createdAt, favorite status, source URL, and image (a new image is
     * only downloaded if the recipe doesn't have one).
     *
     * @param recipeId The ID of the recipe to regenerate
     * @param model The AI model to use (null = use current setting)
     * @param thinkingEnabled Whether to use extended thinking (null = use current setting)
     * @param onProgress Callback for progress updates
     */
    suspend fun execute(
        recipeId: String,
        model: String? = null,
        thinkingEnabled: Boolean? = null,
        onProgress: suspend (RegenerateProgress) -> Unit = {}
    ): RegenerateResult {
        // Load existing recipe
        val existingRecipe = recipeRepository.getRecipeByIdOnce(recipeId)
            ?: return RegenerateResult.Error("Recipe not found")

        val sourceUrl = existingRecipe.sourceUrl
        if (sourceUrl.isNullOrBlank()) {
            return RegenerateResult.NoSourceUrl
        }

        // Fetch fresh HTML from the source URL
        onProgress(RegenerateProgress.FetchingFromUrl)
        val pageResult = webScraperService.fetchPage(sourceUrl)
        if (pageResult.isFailure) {
            val error = pageResult.exceptionOrNull()
            return RegenerateResult.Error(
                error?.message ?: "Failed to fetch page",
                (error as? PageFetchException)?.isTransient == true
            )
        }
        val page = pageResult.getOrThrow()

        // Re-parse with AI (don't save yet, we need to adjust the recipe)
        val parseResult = parseHtmlUseCase.parseHtml(
            html = page.originalHtml,
            sourceUrl = existingRecipe.sourceUrl,
            imageUrl = page.imageUrl,
            saveRecipe = false,
            model = model,
            thinkingEnabled = thinkingEnabled,
            onProgress = { progress ->
                when (progress) {
                    is ParseHtmlUseCase.ParseProgress.ExtractingContent -> {}
                    is ParseHtmlUseCase.ParseProgress.ParsingRecipe ->
                        onProgress(RegenerateProgress.ParsingRecipe)
                    is ParseHtmlUseCase.ParseProgress.RecipeNameAvailable ->
                        onProgress(RegenerateProgress.RecipeNameAvailable(progress.name))
                    is ParseHtmlUseCase.ParseProgress.SavingRecipe ->
                        onProgress(RegenerateProgress.SavingRecipe)
                    is ParseHtmlUseCase.ParseProgress.Complete -> {}
                }
            }
        )

        return when (parseResult) {
            is ParseHtmlUseCase.ParseResult.Success -> {
                // Re-read in case the user changed the image while this was running.
                val freshRecipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: existingRecipe
                val pageImageUrl = parseResult.recipe.sourceImageUrl
                val downloadedImage = if (freshRecipe.imageUrl == null && pageImageUrl != null) {
                    imageDownloadService.downloadAndStoreWithResult(pageImageUrl)
                } else {
                    null
                }

                onProgress(RegenerateProgress.SavingRecipe)
                val regeneratedRecipe = withContext(NonCancellable) {
                    val newImageUrl = downloadedImage?.localUri?.let { imageDownloadService.storeImage(it) }
                    parseResult.recipe.copy(
                        id = freshRecipe.id,
                        createdAt = freshRecipe.createdAt,
                        updatedAt = Clock.System.now(),
                        isFavorite = freshRecipe.isFavorite,
                        sourceUrl = freshRecipe.sourceUrl,
                        imageUrl = freshRecipe.imageUrl ?: newImageUrl,
                        sourceImageUrl = downloadedImage?.effectiveUrl ?: pageImageUrl ?: freshRecipe.sourceImageUrl,
                        userNotes = freshRecipe.userNotes
                    ).also { recipeRepository.saveRecipe(it) }
                }

                val result = RegenerateResult.Success(regeneratedRecipe)
                onProgress(RegenerateProgress.Complete(result))
                result
            }
            is ParseHtmlUseCase.ParseResult.Error ->
                RegenerateResult.Error(parseResult.message, parseResult.retryable)
            ParseHtmlUseCase.ParseResult.NoApiKey ->
                RegenerateResult.NoApiKey
        }
    }
}
