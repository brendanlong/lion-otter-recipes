package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ImportRecipeUseCase @Inject constructor(
    private val webScraperService: WebScraperService,
    private val parseHtmlUseCase: ParseHtmlUseCase,
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
        // Check for API key early
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            return ImportResult.NoApiKey
        }

        // Fetch page content
        onProgress(ImportProgress.FetchingPage)
        val pageResult = webScraperService.fetchPage(url)
        if (pageResult.isFailure) {
            return ImportResult.Error("Failed to fetch page: ${pageResult.exceptionOrNull()?.message}")
        }
        val page = pageResult.getOrThrow()

        // Use ParseHtmlUseCase to parse the HTML content
        val parseResult = parseHtmlUseCase.execute(
            html = page.originalHtml,
            sourceUrl = url,
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
                    is ParseHtmlUseCase.ParseProgress.Complete -> {
                        // Will be handled by the return value
                    }
                }
            }
        )

        return when (parseResult) {
            is ParseHtmlUseCase.ParseResult.Success -> {
                onProgress(ImportProgress.Complete(ImportResult.Success(parseResult.recipe)))
                ImportResult.Success(parseResult.recipe)
            }
            is ParseHtmlUseCase.ParseResult.Error -> {
                ImportResult.Error(parseResult.message)
            }
            ParseHtmlUseCase.ParseResult.NoApiKey -> {
                ImportResult.NoApiKey
            }
        }
    }
}
