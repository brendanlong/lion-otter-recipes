package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * When editing in place only the AI-generated content changes (see
 * [Recipe.withRegeneratedContent]); the ID, image, source, favorite status and notes are kept.
 */
class EditRecipeUseCase @Inject constructor(
    private val parseHtmlUseCase: ParseHtmlUseCase,
    private val recipeRepository: RecipeRepository
) {
    sealed class EditProgress {
        object ParsingRecipe : EditProgress()
        data class RecipeNameAvailable(val name: String) : EditProgress()
        object SavingRecipe : EditProgress()
    }

    /**
     * Edit a recipe by re-parsing user-modified markdown text with AI.
     *
     * @param recipeId The ID of the recipe being edited
     * @param markdownText The user-edited markdown text to parse
     * @param saveAsCopy If true, saves as a new recipe instead of updating the existing one
     * @param copyRecipeId ID for the copy when [saveAsCopy] is set (random if null)
     * @param model The AI model to use (null = use current setting)
     * @param thinkingEnabled Whether to use extended thinking (null = use current setting)
     * @param aiInstructions Optional user instructions for the AI (e.g. "Make this recipe vegan")
     * @param onProgress Callback for progress updates
     */
    suspend fun execute(
        recipeId: String,
        markdownText: String,
        saveAsCopy: Boolean = false,
        copyRecipeId: String? = null,
        model: String? = null,
        thinkingEnabled: Boolean? = null,
        aiInstructions: String? = null,
        onProgress: suspend (EditProgress) -> Unit = {}
    ): Outcome<Recipe> {
        // Load existing recipe to preserve metadata
        val existingRecipe = recipeRepository.getRecipeByIdOnce(recipeId)
            ?: return Outcome.Failure(RecipeError.RecipeNotFound)

        // Collect existing densities to merge with defaults for the AI
        val densityOverrides = RecipeMarkdownFormatter.collectDensities(existingRecipe)
            .ifEmpty { null }

        // Parse the edited markdown text with AI (don't save yet). The image is kept as-is,
        // so no image URL is passed.
        val parseResult = parseHtmlUseCase.parseText(
            text = markdownText,
            sourceUrl = existingRecipe.sourceUrl,
            imageUrl = null,
            saveRecipe = false,
            model = model,
            thinkingEnabled = thinkingEnabled,
            densityOverrides = densityOverrides,
            aiInstructions = aiInstructions,
            onProgress = { progress ->
                when (progress) {
                    is ParseHtmlUseCase.ParseProgress.ExtractingContent -> {}
                    is ParseHtmlUseCase.ParseProgress.ParsingRecipe ->
                        onProgress(EditProgress.ParsingRecipe)
                    is ParseHtmlUseCase.ParseProgress.RecipeNameAvailable ->
                        onProgress(EditProgress.RecipeNameAvailable(progress.name))
                    is ParseHtmlUseCase.ParseProgress.SavingRecipe ->
                        onProgress(EditProgress.SavingRecipe)
                }
            }
        )

        return when (parseResult) {
            is Outcome.Success -> {
                val parsed = parseResult.value
                // Re-load existing recipe to pick up any image changes the user
                // saved directly (without AI) while the worker was queued.
                val freshRecipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: existingRecipe
                val now = Clock.System.now()
                val editedRecipe = if (saveAsCopy) {
                    // Save as a new recipe with new ID and timestamps
                    parsed.copy(
                        id = copyRecipeId ?: java.util.UUID.randomUUID().toString(),
                        createdAt = now,
                        updatedAt = now,
                        isFavorite = false,
                        sourceUrl = freshRecipe.sourceUrl,
                        imageUrl = freshRecipe.imageUrl,
                        sourceImageUrl = freshRecipe.sourceImageUrl
                    )
                } else {
                    freshRecipe.withRegeneratedContent(parsed, now)
                }

                onProgress(EditProgress.SavingRecipe)
                withContext(NonCancellable) {
                    recipeRepository.saveRecipe(editedRecipe)
                }

                Outcome.Success(editedRecipe)
            }
            is Outcome.Failure -> parseResult
        }
    }
}
