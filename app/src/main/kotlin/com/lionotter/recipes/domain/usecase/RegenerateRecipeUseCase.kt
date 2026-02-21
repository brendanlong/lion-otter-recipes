package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlin.time.Clock
import javax.inject.Inject

class RegenerateRecipeUseCase @Inject constructor(
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val recipeRepository: RecipeRepository,
    private val webScraperService: WebScraperService
) {
    sealed class RegenerateResult {
        data class Success(val recipe: Recipe) : RegenerateResult()
        data class Error(val message: String) : RegenerateResult()
        object NoApiKey : RegenerateResult()
        object NoOriginalHtml : RegenerateResult()
    }

    sealed class RegenerateProgress {
        object FetchingFromUrl : RegenerateProgress()
        object ParsingRecipe : RegenerateProgress()
        data class RecipeNameAvailable(val name: String) : RegenerateProgress()
        object SavingRecipe : RegenerateProgress()
        data class Complete(val result: RegenerateResult) : RegenerateProgress()
    }

    /**
     * Regenerate a recipe by re-parsing its original HTML with possibly different AI settings.
     * If no cached HTML is available but the recipe has a source URL, fetches fresh HTML from the URL.
     * Preserves the recipe ID, createdAt, favorite status, and source URL.
     *
     * @param recipeId The ID of the recipe to regenerate
     * @param model The AI model to use (null = use current setting)
     * @param extendedThinking Whether to use extended thinking (null = use current setting)
     * @param onProgress Callback for progress updates
     */
    suspend fun execute(
        recipeId: String,
        model: String? = null,
        extendedThinking: Boolean? = null,
        onProgress: suspend (RegenerateProgress) -> Unit = {}
    ): RegenerateResult {
        // Load existing recipe
        val existingRecipe = recipeRepository.getRecipeByIdOnce(recipeId)
            ?: return RegenerateResult.Error("Recipe not found")

        // Load original HTML, or fetch from source URL if not cached
        var originalHtml = recipeRepository.getOriginalHtml(recipeId)
        var fetchedImageUrl: String? = null
        if (originalHtml.isNullOrBlank()) {
            val sourceUrl = existingRecipe.sourceUrl
            if (sourceUrl.isNullOrBlank()) {
                return RegenerateResult.NoOriginalHtml
            }

            // Fetch fresh HTML from the source URL
            onProgress(RegenerateProgress.FetchingFromUrl)
            val pageResult = webScraperService.fetchPage(sourceUrl)
            if (pageResult.isFailure) {
                return RegenerateResult.Error(
                    "Failed to fetch page: ${pageResult.exceptionOrNull()?.message}"
                )
            }
            val page = pageResult.getOrThrow()
            originalHtml = page.originalHtml
            fetchedImageUrl = page.imageUrl
        }

        // Re-parse with AI (don't save yet, we need to adjust the recipe)
        val parseResult = parseHtmlUseCase.parseHtml(
            html = originalHtml,
            sourceUrl = existingRecipe.sourceUrl,
            imageUrl = fetchedImageUrl ?: existingRecipe.imageUrl,
            saveRecipe = false,
            model = model,
            extendedThinking = extendedThinking,
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
                // Overwrite with same ID, preserve createdAt and favorite status
                val regeneratedRecipe = parseResult.recipe.copy(
                    id = existingRecipe.id,
                    createdAt = existingRecipe.createdAt,
                    updatedAt = Clock.System.now(),
                    isFavorite = existingRecipe.isFavorite,
                    sourceUrl = existingRecipe.sourceUrl,
                    imageUrl = parseResult.recipe.imageUrl ?: existingRecipe.imageUrl,
                    userNotes = existingRecipe.userNotes
                )

                onProgress(RegenerateProgress.SavingRecipe)
                recipeRepository.saveRecipe(regeneratedRecipe, originalHtml = originalHtml)

                val result = RegenerateResult.Success(regeneratedRecipe)
                onProgress(RegenerateProgress.Complete(result))
                result
            }
            is ParseHtmlUseCase.ParseResult.Error ->
                RegenerateResult.Error(parseResult.message)
            ParseHtmlUseCase.ParseResult.NoApiKey ->
                RegenerateResult.NoApiKey
        }
    }
}
