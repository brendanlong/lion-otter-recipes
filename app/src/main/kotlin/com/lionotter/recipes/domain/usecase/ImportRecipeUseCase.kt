package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject

class ImportRecipeUseCase @Inject constructor(
    private val webScraperService: WebScraperService,
    private val anthropicService: AnthropicService,
    private val recipeRepository: RecipeRepository,
    private val settingsDataStore: SettingsDataStore
) {
    sealed class ImportResult {
        data class Success(val recipe: Recipe) : ImportResult()
        data class Error(val message: String) : ImportResult()
        object NoApiKey : ImportResult()
    }

    sealed class ImportProgress {
        object FetchingPage : ImportProgress()
        object ParsingRecipe : ImportProgress()
        data class RecipeNameAvailable(val name: String) : ImportProgress()
        object SavingRecipe : ImportProgress()
        data class Complete(val result: ImportResult) : ImportProgress()
    }

    suspend fun execute(
        url: String,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult {
        // Check for API key
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            return ImportResult.NoApiKey
        }

        val model = settingsDataStore.aiModel.first()

        // Fetch and extract page content
        onProgress(ImportProgress.FetchingPage)
        val pageResult = webScraperService.fetchPage(url)
        if (pageResult.isFailure) {
            return ImportResult.Error("Failed to fetch page: ${pageResult.exceptionOrNull()?.message}")
        }
        val page = pageResult.getOrThrow()

        // Parse with AI (using extracted content to reduce token usage)
        onProgress(ImportProgress.ParsingRecipe)
        val parseResult = anthropicService.parseRecipe(page.extractedContent, apiKey, model)
        if (parseResult.isFailure) {
            return ImportResult.Error("Failed to parse recipe: ${parseResult.exceptionOrNull()?.message}")
        }
        val parsed = parseResult.getOrThrow()

        // Notify that recipe name is available
        onProgress(ImportProgress.RecipeNameAvailable(parsed.name))

        // Create Recipe
        val now = Clock.System.now()
        val recipe = Recipe(
            id = UUID.randomUUID().toString(),
            name = parsed.name,
            sourceUrl = url,
            story = parsed.story,
            servings = parsed.servings,
            prepTime = parsed.prepTime,
            cookTime = parsed.cookTime,
            totalTime = parsed.totalTime,
            ingredientSections = parsed.ingredientSections,
            instructionSections = parsed.instructionSections,
            tags = parsed.tags,
            imageUrl = page.imageUrl,
            createdAt = now,
            updatedAt = now
        )

        // Save to database (keep original HTML for potential re-parsing)
        onProgress(ImportProgress.SavingRecipe)
        recipeRepository.saveRecipe(recipe, originalHtml = page.originalHtml)

        onProgress(ImportProgress.Complete(ImportResult.Success(recipe)))
        return ImportResult.Success(recipe)
    }
}
