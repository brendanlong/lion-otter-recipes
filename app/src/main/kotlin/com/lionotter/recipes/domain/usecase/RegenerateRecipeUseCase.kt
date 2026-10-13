package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.PageFetcher
import com.lionotter.recipes.domain.repository.RecipeRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import javax.inject.Inject

class RegenerateRecipeUseCase @Inject constructor(
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val recipeRepository: RecipeRepository,
    private val pageFetcher: PageFetcher,
    private val imageStore: ImageStore
) {
    sealed class RegenerateProgress {
        object FetchingFromUrl : RegenerateProgress()
        object ParsingRecipe : RegenerateProgress()
        data class RecipeNameAvailable(val name: String) : RegenerateProgress()
        object SavingRecipe : RegenerateProgress()
    }

    /**
     * Regenerate a recipe by re-fetching its source URL and re-parsing with AI.
     * Requires the recipe to have a source URL.
     * Only the AI-generated content changes (see [Recipe.withRegeneratedContent]); the image is
     * kept (a new one is only downloaded if the recipe doesn't have one).
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
    ): Outcome<Recipe> {
        val existingRecipe = recipeRepository.getRecipeByIdOnce(recipeId)
            ?: return Outcome.Failure(RecipeError.RecipeNotFound)

        val sourceUrl = existingRecipe.sourceUrl
        if (sourceUrl.isNullOrBlank()) {
            return Outcome.Failure(RecipeError.NoSourceUrl)
        }

        // Fetch fresh HTML from the source URL
        onProgress(RegenerateProgress.FetchingFromUrl)
        val page = when (val pageResult = pageFetcher.fetchPage(sourceUrl)) {
            is Outcome.Success -> pageResult.value
            is Outcome.Failure -> return pageResult
        }

        // Re-parse with AI (don't save yet, we need to adjust the recipe)
        val parseResult = parseHtmlUseCase.parseHtml(
            html = page.html,
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
                }
            }
        )

        return when (parseResult) {
            is Outcome.Success -> {
                val parsed = parseResult.value
                // Re-read in case the user changed the image while this was running.
                val freshRecipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: existingRecipe
                val pageImageUrl = parsed.sourceImageUrl
                val downloadedImage = if (freshRecipe.imageUrl == null && pageImageUrl != null) {
                    imageStore.downloadAndStoreWithResult(pageImageUrl)
                } else {
                    null
                }

                onProgress(RegenerateProgress.SavingRecipe)
                val regeneratedRecipe = withContext(NonCancellable) {
                    val newImageUrl = downloadedImage?.localUri?.let { imageStore.storeImage(it) }
                    freshRecipe.withRegeneratedContent(parsed, Clock.System.now()).copy(
                        imageUrl = freshRecipe.imageUrl ?: newImageUrl,
                        sourceImageUrl = downloadedImage?.effectiveUrl ?: pageImageUrl ?: freshRecipe.sourceImageUrl
                    ).also { recipeRepository.saveRecipe(it) }
                }

                Outcome.Success(regeneratedRecipe)
            }
            is Outcome.Failure -> parseResult
        }
    }
}
