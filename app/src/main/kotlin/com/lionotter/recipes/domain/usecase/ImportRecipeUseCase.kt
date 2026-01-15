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

        // Fetch HTML
        onProgress(ImportProgress.FetchingPage)
        val htmlResult = webScraperService.fetchHtml(url)
        if (htmlResult.isFailure) {
            return ImportResult.Error("Failed to fetch page: ${htmlResult.exceptionOrNull()?.message}")
        }
        val html = htmlResult.getOrThrow()

        // Parse with AI
        onProgress(ImportProgress.ParsingRecipe)
        val parseResult = anthropicService.parseRecipe(html, apiKey, model)
        if (parseResult.isFailure) {
            return ImportResult.Error("Failed to parse recipe: ${parseResult.exceptionOrNull()?.message}")
        }
        val parsed = parseResult.getOrThrow()

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
            imageUrl = parsed.imageUrl,
            createdAt = now,
            updatedAt = now
        )

        // Save to database
        onProgress(ImportProgress.SavingRecipe)
        recipeRepository.saveRecipe(recipe, originalHtml = html)

        onProgress(ImportProgress.Complete(ImportResult.Success(recipe)))
        return ImportResult.Success(recipe)
    }
}
