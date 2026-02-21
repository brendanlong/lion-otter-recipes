package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import kotlin.time.Clock
import javax.inject.Inject

/**
 * Use case for editing a recipe by sending user-modified markdown text through the AI
 * for re-parsing. The AI cleans up formatting and regenerates structured data (including
 * ingredient densities) using the standard recipe import prompt.
 *
 * Passes existing ingredient densities as hints to the AI so cheaper models (like Haiku)
 * can reuse known values instead of re-deriving them.
 *
 * Preserves the recipe's ID, createdAt, favorite status, image, and source URL.
 */
class EditRecipeUseCase @Inject constructor(
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val recipeRepository: RecipeRepository
) {
    sealed class EditResult {
        data class Success(val recipe: Recipe) : EditResult()
        data class Error(val message: String) : EditResult()
        object NoApiKey : EditResult()
    }

    sealed class EditProgress {
        object ParsingRecipe : EditProgress()
        data class RecipeNameAvailable(val name: String) : EditProgress()
        object SavingRecipe : EditProgress()
        data class Complete(val result: EditResult) : EditProgress()
    }

    /**
     * Edit a recipe by re-parsing user-modified markdown text with AI.
     *
     * @param recipeId The ID of the recipe being edited
     * @param markdownText The user-edited markdown text to parse
     * @param saveAsCopy If true, saves as a new recipe instead of updating the existing one
     * @param model The AI model to use (null = use current setting)
     * @param extendedThinking Whether to use extended thinking (null = use current setting)
     * @param onProgress Callback for progress updates
     */
    suspend fun execute(
        recipeId: String,
        markdownText: String,
        saveAsCopy: Boolean = false,
        model: String? = null,
        extendedThinking: Boolean? = null,
        onProgress: suspend (EditProgress) -> Unit = {}
    ): EditResult {
        // Load existing recipe to preserve metadata
        val existingRecipe = recipeRepository.getRecipeByIdOnce(recipeId)
            ?: return EditResult.Error("Recipe not found")

        // Preserve original HTML for future regeneration
        val originalHtml = recipeRepository.getOriginalHtml(recipeId)

        // Collect existing densities to merge with defaults for the AI
        val densityOverrides = RecipeMarkdownFormatter.collectDensities(existingRecipe)
            .ifEmpty { null }

        // Parse the edited markdown text with AI (don't save yet)
        val parseResult = parseHtmlUseCase.parseText(
            text = markdownText,
            sourceUrl = existingRecipe.sourceUrl,
            imageUrl = existingRecipe.sourceImageUrl ?: existingRecipe.imageUrl,
            saveRecipe = false,
            originalHtml = originalHtml,
            model = model,
            extendedThinking = extendedThinking,
            densityOverrides = densityOverrides,
            onProgress = { progress ->
                when (progress) {
                    is ParseHtmlUseCase.ParseProgress.ExtractingContent -> {}
                    is ParseHtmlUseCase.ParseProgress.ParsingRecipe ->
                        onProgress(EditProgress.ParsingRecipe)
                    is ParseHtmlUseCase.ParseProgress.RecipeNameAvailable ->
                        onProgress(EditProgress.RecipeNameAvailable(progress.name))
                    is ParseHtmlUseCase.ParseProgress.SavingRecipe ->
                        onProgress(EditProgress.SavingRecipe)
                    is ParseHtmlUseCase.ParseProgress.Complete -> {}
                }
            }
        )

        return when (parseResult) {
            is ParseHtmlUseCase.ParseResult.Success -> {
                // Re-load existing recipe to pick up any image changes the user
                // saved directly (without AI) while the worker was queued.
                val freshRecipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: existingRecipe
                val now = Clock.System.now()
                val editedRecipe = if (saveAsCopy) {
                    // Save as a new recipe with new ID and timestamps
                    parseResult.recipe.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        createdAt = now,
                        updatedAt = now,
                        isFavorite = false,
                        sourceUrl = freshRecipe.sourceUrl,
                        // Use the user's current image, not the AI's
                        imageUrl = freshRecipe.imageUrl,
                        sourceImageUrl = parseResult.recipe.sourceImageUrl ?: freshRecipe.sourceImageUrl
                    )
                } else {
                    // Overwrite with same ID, preserve key metadata
                    parseResult.recipe.copy(
                        id = freshRecipe.id,
                        createdAt = freshRecipe.createdAt,
                        updatedAt = now,
                        isFavorite = freshRecipe.isFavorite,
                        sourceUrl = freshRecipe.sourceUrl,
                        // Use the user's current image, not the AI's
                        imageUrl = freshRecipe.imageUrl,
                        sourceImageUrl = parseResult.recipe.sourceImageUrl ?: freshRecipe.sourceImageUrl,
                        userNotes = freshRecipe.userNotes
                    )
                }

                onProgress(EditProgress.SavingRecipe)
                recipeRepository.saveRecipe(editedRecipe, originalHtml = originalHtml)

                val result = EditResult.Success(editedRecipe)
                onProgress(EditProgress.Complete(result))
                result
            }
            is ParseHtmlUseCase.ParseResult.Error ->
                EditResult.Error(parseResult.message)
            ParseHtmlUseCase.ParseResult.NoApiKey ->
                EditResult.NoApiKey
        }
    }
}
