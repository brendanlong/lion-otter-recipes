package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.local.ImportDebugEntity
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.RecipeParseException
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Use case for parsing content into a Recipe using AI.
 *
 * Provides two entry points:
 * - [parseHtml]: extracts readable content from raw HTML via Readability4J, then parses with AI
 * - [parseText]: parses pre-extracted text directly with AI (used by Paprika import)
 */
class ParseHtmlUseCase @Inject constructor(
    private val anthropicService: AnthropicService,
    private val recipeRepository: RecipeRepository,
    private val importDebugRepository: ImportDebugRepository,
    private val settingsDataStore: SettingsDataStore,
    private val imageDownloadService: ImageDownloadService
) {
    companion object {
        private const val TAG = "ParseHtmlUseCase"
    }
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
     * Parse raw HTML content into a Recipe using AI.
     * Extracts readable content via Readability4J before sending to the AI.
     *
     * @param html The raw HTML content
     * @param sourceUrl Optional URL for the recipe source
     * @param imageUrl Optional image URL extracted from the page
     * @param saveRecipe Whether to save the recipe to the database (default true)
     * @param model AI model override (null = use current setting)
     * @param thinkingEnabled Extended thinking override (null = use current setting)
     * @param onProgress Callback for progress updates
     * @return The parsed recipe or error
     */
    suspend fun parseHtml(
        html: String,
        sourceUrl: String? = null,
        imageUrl: String? = null,
        saveRecipe: Boolean = true,
        model: String? = null,
        thinkingEnabled: Boolean? = null,
        onProgress: suspend (ParseProgress) -> Unit = {}
    ): ParseResult {
        onProgress(ParseProgress.ExtractingContent)
        val extractedContent = extractContent(html, sourceUrl)
        val extractedImageUrl = imageUrl ?: extractImageUrl(html)

        return parseText(
            text = extractedContent,
            sourceUrl = sourceUrl,
            imageUrl = extractedImageUrl,
            saveRecipe = saveRecipe,
            originalHtml = html,
            model = model,
            thinkingEnabled = thinkingEnabled,
            onProgress = onProgress
        )
    }

    /**
     * Parse pre-extracted text content into a Recipe using AI.
     * Skips HTML extraction — use this when content is already in text form
     * (e.g., Paprika import).
     *
     * @param text The pre-extracted text content to send to the AI
     * @param sourceUrl Optional URL for the recipe source
     * @param imageUrl Optional image URL for the recipe
     * @param saveRecipe Whether to save the recipe to the database (default true)
     * @param originalHtml Optional original HTML for debug data and storage alongside the recipe
     * @param model AI model override (null = use current setting)
     * @param thinkingEnabled Extended thinking override (null = use current setting)
     * @param densityOverrides Optional density overrides from existing recipe (merged with defaults for cheaper editing)
     * @param onProgress Callback for progress updates
     * @return The parsed recipe or error
     */
    suspend fun parseText(
        text: String,
        sourceUrl: String? = null,
        imageUrl: String? = null,
        saveRecipe: Boolean = true,
        originalHtml: String? = null,
        model: String? = null,
        thinkingEnabled: Boolean? = null,
        densityOverrides: Map<String, Double>? = null,
        onProgress: suspend (ParseProgress) -> Unit = {}
    ): ParseResult {
        // Check for API key
        val apiKey = settingsDataStore.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            return ParseResult.NoApiKey
        }

        val model = model ?: settingsDataStore.aiModel.first()
        val thinkingEnabled = thinkingEnabled ?: settingsDataStore.thinkingEnabled.first()
        val debuggingEnabled = settingsDataStore.importDebuggingEnabled.first()

        // Parse with AI
        onProgress(ParseProgress.ParsingRecipe)
        val startTime = System.currentTimeMillis()
        val parseResult = anthropicService.parseRecipe(text, apiKey, model, thinkingEnabled, densityOverrides)
        val durationMs = System.currentTimeMillis() - startTime
        if (parseResult.isFailure) {
            val errorMessage = "Failed to parse recipe: ${parseResult.exceptionOrNull()?.message}"

            if (debuggingEnabled) {
                val exception = parseResult.exceptionOrNull()
                val aiErrorMessage = if (exception is RecipeParseException) {
                    exception.message
                } else {
                    exception?.message
                }
                saveDebugEntry(
                    sourceUrl = sourceUrl,
                    originalHtml = originalHtml,
                    cleanedContent = text,
                    aiOutputJson = null,
                    inputTokens = null,
                    outputTokens = null,
                    aiModel = model,
                    thinkingEnabled = thinkingEnabled,
                    recipeId = null,
                    recipeName = null,
                    errorMessage = aiErrorMessage,
                    isError = true,
                    durationMs = durationMs
                )
            }

            return ParseResult.Error(errorMessage)
        }
        val parsedWithUsage = parseResult.getOrThrow()
        val parsed = parsedWithUsage.result

        // Check for cancellation before proceeding to save
        coroutineContext.ensureActive()

        // Notify that recipe name is available
        onProgress(ParseProgress.RecipeNameAvailable(parsed.name))

        // Download image locally if available
        val downloadResult = imageUrl?.let { imageDownloadService.downloadAndStoreWithResult(it) }

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
            instructionSections = parsed.instructionSections,
            equipment = parsed.equipment,
            tags = parsed.tags,
            imageUrl = downloadResult?.localUri,
            sourceImageUrl = downloadResult?.effectiveUrl ?: imageUrl,
            createdAt = now,
            updatedAt = now
        )

        // Save to database if requested
        if (saveRecipe) {
            coroutineContext.ensureActive()
            onProgress(ParseProgress.SavingRecipe)
            recipeRepository.saveRecipe(recipe, originalHtml = originalHtml)
        }

        // Save debug data on success if debugging is enabled
        if (debuggingEnabled) {
            saveDebugEntry(
                sourceUrl = sourceUrl,
                originalHtml = originalHtml,
                cleanedContent = text,
                aiOutputJson = parsedWithUsage.aiOutputJson,
                inputTokens = parsedWithUsage.inputTokens,
                outputTokens = parsedWithUsage.outputTokens,
                aiModel = model,
                thinkingEnabled = thinkingEnabled,
                recipeId = recipe.id,
                recipeName = recipe.name,
                errorMessage = null,
                isError = false,
                durationMs = durationMs
            )
        }

        onProgress(ParseProgress.Complete(ParseResult.Success(recipe)))
        return ParseResult.Success(recipe)
    }

    private suspend fun saveDebugEntry(
        sourceUrl: String?,
        originalHtml: String?,
        cleanedContent: String?,
        aiOutputJson: String?,
        inputTokens: Long?,
        outputTokens: Long?,
        aiModel: String?,
        thinkingEnabled: Boolean,
        recipeId: String?,
        recipeName: String?,
        errorMessage: String?,
        isError: Boolean,
        durationMs: Long?
    ) {
        val entry = ImportDebugEntity(
            id = UUID.randomUUID().toString(),
            sourceUrl = sourceUrl,
            originalHtml = originalHtml,
            cleanedContent = cleanedContent,
            aiOutputJson = aiOutputJson,
            originalLength = originalHtml?.length ?: 0,
            cleanedLength = cleanedContent?.length ?: 0,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            aiModel = aiModel,
            thinkingEnabled = thinkingEnabled,
            recipeId = recipeId,
            recipeName = recipeName,
            errorMessage = errorMessage,
            isError = isError,
            durationMs = durationMs,
            createdAt = Clock.System.now().toEpochMilliseconds()
        )
        importDebugRepository.saveDebugEntry(entry)
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
            Log.w(TAG, "Failed to extract content from HTML, falling back to raw HTML", e)
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
            Log.w(TAG, "Failed to extract image URL from HTML", e)
            null
        }
    }
}
