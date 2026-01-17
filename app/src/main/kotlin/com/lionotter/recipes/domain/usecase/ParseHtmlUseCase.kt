package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import java.util.UUID
import javax.inject.Inject

/**
 * Use case for parsing HTML content into a Recipe using AI.
 * This is a reusable helper that can be called from:
 * - ImportRecipeUseCase (URL-based import)
 * - Google Drive import (HTML fallback)
 */
class ParseHtmlUseCase @Inject constructor(
    private val anthropicService: AnthropicService,
    private val recipeRepository: RecipeRepository,
    private val settingsDataStore: SettingsDataStore
) {
    sealed class ParseResult {
        data class Success(val recipe: Recipe) : ParseResult()
        data class Error(val message: String) : ParseResult()
        object NoApiKey : ParseResult()
    }

    sealed class ParseProgress {
        object ExtractingContent : ParseProgress()
        object ParsingRecipe : ParseProgress()
        data class RecipeNameAvailable(val name: String) : ParseProgress()
        object SavingRecipe : ParseProgress()
        data class Complete(val result: ParseResult) : ParseProgress()
    }

    /**
     * Parse HTML content into a Recipe using AI.
     *
     * @param html The raw HTML content
     * @param sourceUrl Optional URL for the recipe source
     * @param imageUrl Optional image URL extracted from the page
     * @param saveRecipe Whether to save the recipe to the database (default true)
     * @param onProgress Callback for progress updates
     * @return The parsed recipe or error
     */
    suspend fun execute(
        html: String,
        sourceUrl: String? = null,
        imageUrl: String? = null,
        saveRecipe: Boolean = true,
        onProgress: suspend (ParseProgress) -> Unit = {}
    ): ParseResult {
        // Check for API key
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            return ParseResult.NoApiKey
        }

        val model = settingsDataStore.aiModel.first()

        // Extract content from HTML
        onProgress(ParseProgress.ExtractingContent)
        val extractedContent = extractContent(html, sourceUrl)
        val extractedImageUrl = imageUrl ?: extractImageUrl(html)

        // Parse with AI
        onProgress(ParseProgress.ParsingRecipe)
        val parseResult = anthropicService.parseRecipe(extractedContent, apiKey, model)
        if (parseResult.isFailure) {
            return ParseResult.Error("Failed to parse recipe: ${parseResult.exceptionOrNull()?.message}")
        }
        val parsed = parseResult.getOrThrow()

        // Notify that recipe name is available
        onProgress(ParseProgress.RecipeNameAvailable(parsed.name))

        // Create Recipe
        val now = Clock.System.now()
        val recipe = Recipe(
            id = UUID.randomUUID().toString(),
            name = parsed.name,
            sourceUrl = sourceUrl,
            story = parsed.story,
            servings = parsed.servings,
            prepTime = parsed.prepTime,
            cookTime = parsed.cookTime,
            totalTime = parsed.totalTime,
            ingredientSections = parsed.ingredientSections,
            instructionSections = parsed.instructionSections,
            tags = parsed.tags,
            imageUrl = extractedImageUrl,
            createdAt = now,
            updatedAt = now
        )

        // Save to database if requested
        if (saveRecipe) {
            onProgress(ParseProgress.SavingRecipe)
            recipeRepository.saveRecipe(recipe, originalHtml = html)
        }

        onProgress(ParseProgress.Complete(ParseResult.Success(recipe)))
        return ParseResult.Success(recipe)
    }

    private fun extractContent(html: String, sourceUrl: String?): String {
        return try {
            val url = sourceUrl ?: "https://example.com"
            val readability = Readability4J(url, html)
            val article = readability.parse()

            val title = article.title
            val content = article.textContent?.takeIf { it.isNotBlank() }
                ?: article.content
                ?: html

            buildString {
                if (!title.isNullOrBlank()) {
                    appendLine("Title: $title")
                    appendLine()
                }
                append(content)
            }
        } catch (e: Exception) {
            html
        }
    }

    private fun extractImageUrl(html: String): String? {
        return try {
            val doc = Jsoup.parse(html)
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
