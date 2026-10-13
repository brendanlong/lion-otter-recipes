package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.logDescription
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.tagVocabulary
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.ImportDebugLog
import com.lionotter.recipes.domain.port.ImportDebugRecord
import com.lionotter.recipes.domain.port.PageContentExtractor
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipe
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.port.RecipeParser
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Use case for parsing content into a Recipe using AI.
 *
 * Provides two entry points:
 * - [parseHtml]: extracts JSON-LD and readable text from raw HTML, then parses with AI
 * - [parseText]: parses pre-extracted text directly with AI (used by Paprika import)
 */
class ParseHtmlUseCase @Inject constructor(
    private val recipeParser: RecipeParser,
    private val pageContentExtractor: PageContentExtractor,
    private val recipeRepository: RecipeRepository,
    private val importDebugLog: ImportDebugLog,
    private val aiSettings: AiSettings,
    private val imageStore: ImageStore,
    private val imageScheduler: RecipeImageScheduler
) {
    sealed class ParseProgress {
        object ExtractingContent : ParseProgress()
        object ParsingRecipe : ParseProgress()
        data class RecipeNameAvailable(val name: String) : ParseProgress()
        object SavingRecipe : ParseProgress()
    }

    /**
     * Parse raw HTML content into a Recipe using AI.
     * Extracts schema.org JSON-LD and readable text before sending to the AI.
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
    ): Outcome<Recipe> {
        onProgress(ParseProgress.ExtractingContent)
        val pageContent = pageContentExtractor.extract(html, sourceUrl)
        val extractedImageUrl = imageUrl ?: pageContent.imageUrl

        return parseText(
            text = pageContent.text,
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
     * @param imageUrl Optional image URL for the recipe. Only downloaded when [saveRecipe] is true;
     *   otherwise it is returned as [Recipe.sourceImageUrl] and the caller decides what to do with it.
     * @param saveRecipe Whether to save the recipe to the database (default true)
     * @param originalHtml Optional original HTML for debug data
     * @param model AI model override (null = use current setting)
     * @param thinkingEnabled Extended thinking override (null = use current setting)
     * @param densityOverrides Optional density overrides from existing recipe (merged with defaults for cheaper editing)
     * @param aiInstructions Optional user instructions for the AI (e.g. "Make this recipe vegan")
     * @param generateMissingImage Whether a saved recipe without an image gets a generated one
     *   (if image generation is on)
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
        aiInstructions: String? = null,
        generateMissingImage: Boolean = true,
        onProgress: suspend (ParseProgress) -> Unit = {}
    ): Outcome<Recipe> = parse(
        text = text,
        attachments = emptyList(),
        sourceUrl = sourceUrl,
        imageUrl = imageUrl,
        recipePhoto = null,
        saveRecipe = saveRecipe,
        generateMissingImage = generateMissingImage,
        originalHtml = originalHtml,
        model = model,
        thinkingEnabled = thinkingEnabled,
        densityOverrides = densityOverrides,
        aiInstructions = aiInstructions,
        onProgress = onProgress
    )

    /**
     * Parse photos (in page order) or a PDF of a recipe with AI and save the recipe.
     *
     * @param recipePhoto a photo to use as the recipe's image, or null for none
     */
    suspend fun parseAttachments(
        attachments: List<ParseAttachment>,
        recipePhoto: ParseAttachment.Image? = null,
        onProgress: suspend (ParseProgress) -> Unit = {}
    ): Outcome<Recipe> = parse(
        text = "",
        attachments = attachments,
        sourceUrl = null,
        imageUrl = null,
        recipePhoto = recipePhoto,
        saveRecipe = true,
        generateMissingImage = true,
        originalHtml = null,
        model = null,
        thinkingEnabled = null,
        densityOverrides = null,
        aiInstructions = null,
        onProgress = onProgress
    )

    private suspend fun parse(
        text: String,
        attachments: List<ParseAttachment>,
        sourceUrl: String?,
        imageUrl: String?,
        recipePhoto: ParseAttachment.Image?,
        saveRecipe: Boolean,
        generateMissingImage: Boolean,
        originalHtml: String?,
        model: String?,
        thinkingEnabled: Boolean?,
        densityOverrides: Map<String, Double>?,
        aiInstructions: String?,
        onProgress: suspend (ParseProgress) -> Unit
    ): Outcome<Recipe> {
        val apiKey = aiSettings.anthropicApiKey.first()
        if (apiKey.isNullOrBlank()) {
            return Outcome.Failure(RecipeError.NoApiKey)
        }

        // Check recipe count limit before spending AI credits
        if (saveRecipe) {
            val count = recipeRepository.getRecipeCount()
            if (count >= ResourceLimits.MAX_RECIPES) {
                return Outcome.Failure(RecipeError.LimitReached(count, ResourceLimits.MAX_RECIPES))
            }
        }

        val model = AiModels.normalizeModel(model ?: aiSettings.aiModel.first())
        val thinkingEnabled = thinkingEnabled ?: aiSettings.thinkingEnabled.first()
        val debuggingEnabled = aiSettings.importDebuggingEnabled.first()
        // Parse with AI
        onProgress(ParseProgress.ParsingRecipe)
        val existingTags = existingTagVocabulary()
        val startTime = System.currentTimeMillis()
        val parseResult = recipeParser.parse(
            ParseRequest(
                content = text,
                apiKey = apiKey,
                model = model,
                thinkingEnabled = thinkingEnabled,
                densityOverrides = densityOverrides,
                aiInstructions = aiInstructions,
                existingTags = existingTags,
                attachments = attachments
            )
        )
        val debugContent = if (attachments.isEmpty()) text else describeAttachments(attachments)
        val durationMs = System.currentTimeMillis() - startTime
        val parsedWithUsage = when (parseResult) {
            is Outcome.Success -> parseResult.value
            is Outcome.Failure -> {
                if (debuggingEnabled) {
                    saveDebugEntry(
                        sourceUrl = sourceUrl,
                        originalHtml = originalHtml,
                        cleanedContent = debugContent,
                        aiOutputJson = null,
                        inputTokens = null,
                        outputTokens = null,
                        aiModel = model,
                        thinkingEnabled = thinkingEnabled,
                        recipeId = null,
                        recipeName = null,
                        errorMessage = parseResult.error.logDescription(),
                        isError = true,
                        durationMs = durationMs
                    )
                }
                return parseResult
            }
        }
        val parsed = parsedWithUsage.recipe

        // Check for cancellation before proceeding to save
        coroutineContext.ensureActive()

        // Notify that recipe name is available
        onProgress(ParseProgress.RecipeNameAvailable(parsed.name))

        val recipe = if (saveRecipe) {
            saveWithImage(parsed, UUID.randomUUID().toString(), sourceUrl, imageUrl, recipePhoto) {
                onProgress(ParseProgress.SavingRecipe)
            }
        } else {
            // Callers that don't save (edit/regenerate) manage the image themselves, so don't
            // download or upload anything here (that would orphan a copy on every edit).
            buildRecipe(parsed, sourceUrl, storedImageUrl = null, sourceImageUrl = imageUrl)
        }
        if (saveRecipe && generateMissingImage && recipe.imageUrl == null && coroutineContext.isActive &&
            imageGenerationAvailable()
        ) {
            scheduleImageGeneration(recipe.id)
        }

        // Save debug data on success if debugging is enabled
        if (debuggingEnabled) {
            saveDebugEntry(
                sourceUrl = sourceUrl,
                originalHtml = originalHtml,
                cleanedContent = debugContent,
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

        return Outcome.Success(recipe)
    }

    /** The recipe is already saved, so failing to schedule only costs the image. */
    private fun scheduleImageGeneration(recipeId: String) {
        try {
            imageScheduler.scheduleImageGeneration(recipeId)
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to schedule image generation", e)
        }
    }

    private suspend fun imageGenerationAvailable(): Boolean =
        aiSettings.imageGenerationEnabled.first() && !aiSettings.openAiApiKey.first().isNullOrBlank()

    /**
     * The user's most used tags, for the AI to reuse. Only a hint, so if the recipes can't be
     * read the parse goes ahead without it.
     */
    suspend fun existingTagVocabulary(): List<String> = try {
        tagVocabulary(recipeRepository.getAllRecipeTags().values)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.w(TAG, "Couldn't read the existing tags; parsing without them", e)
        emptyList()
    }

    /**
     * Save a pre-parsed batch result as a Recipe.
     * Used by batch import flows where the AI response has already been obtained
     * via the Batch API and parsed into a [ParsedRecipeWithUsage].
     *
     * Handles image download, recipe creation, database save, and debug entry.
     *
     * @param parsedWithUsage The pre-parsed AI result with token usage
     * @param recipeId ID to save under; stable per batch result so a resumed import overwrites
     * @param sourceUrl Optional URL for the recipe source
     * @param imageUrl Optional image URL for the recipe
     * @param originalHtml Optional original HTML for debug data
     * @param cleanedContent The text that was sent to the AI
     * @param model The AI model used
     * @param thinkingEnabled Whether thinking was enabled
     * @param durationMs Time taken for the batch to process (optional)
     * @return The saved Recipe, or why it couldn't be saved
     */
    suspend fun saveBatchResult(
        parsedWithUsage: ParsedRecipeWithUsage,
        recipeId: String,
        sourceUrl: String? = null,
        imageUrl: String? = null,
        originalHtml: String? = null,
        cleanedContent: String? = null,
        model: String,
        thinkingEnabled: Boolean,
        durationMs: Long? = null
    ): Outcome<Recipe> {
        val count = recipeRepository.getRecipeCount()
        if (count >= ResourceLimits.MAX_RECIPES && recipeRepository.getRecipeByIdOnce(recipeId) == null) {
            return Outcome.Failure(RecipeError.LimitReached(count, ResourceLimits.MAX_RECIPES))
        }

        val parsed = parsedWithUsage.recipe

        val recipe = saveWithImage(parsed, recipeId, sourceUrl, imageUrl)

        // Save debug data if debugging is enabled
        val debuggingEnabled = aiSettings.importDebuggingEnabled.first()
        if (debuggingEnabled) {
            saveDebugEntry(
                sourceUrl = sourceUrl,
                originalHtml = originalHtml,
                cleanedContent = cleanedContent,
                aiOutputJson = parsedWithUsage.aiOutputJson,
                inputTokens = parsedWithUsage.inputTokens,
                outputTokens = parsedWithUsage.outputTokens,
                aiModel = model,
                thinkingEnabled = thinkingEnabled,
                recipeId = recipe.id,
                recipeName = recipe.name,
                errorMessage = null,
                isError = false,
                durationMs = durationMs,
                batchMode = true
            )
        }

        return Outcome.Success(recipe)
    }

    /**
     * Save a debug entry for a failed batch result.
     */
    suspend fun saveBatchErrorDebugEntry(
        sourceUrl: String? = null,
        originalHtml: String? = null,
        cleanedContent: String? = null,
        errorMessage: String?,
        model: String,
        thinkingEnabled: Boolean,
        durationMs: Long? = null
    ) {
        val debuggingEnabled = aiSettings.importDebuggingEnabled.first()
        if (debuggingEnabled) {
            saveDebugEntry(
                sourceUrl = sourceUrl,
                originalHtml = originalHtml,
                cleanedContent = cleanedContent,
                aiOutputJson = null,
                inputTokens = null,
                outputTokens = null,
                aiModel = model,
                thinkingEnabled = thinkingEnabled,
                recipeId = null,
                recipeName = null,
                errorMessage = errorMessage,
                isError = true,
                durationMs = durationMs,
                batchMode = true
            )
        }
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
        durationMs: Long?,
        batchMode: Boolean = false
    ) {
        val entry = ImportDebugRecord(
            sourceUrl = sourceUrl,
            originalHtml = originalHtml,
            cleanedContent = cleanedContent,
            aiOutputJson = aiOutputJson,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            aiModel = aiModel,
            thinkingEnabled = thinkingEnabled,
            recipeId = recipeId,
            recipeName = recipeName,
            errorMessage = errorMessage,
            isError = isError,
            durationMs = durationMs,
            batchMode = batchMode
        )
        withContext(NonCancellable) {
            importDebugLog.record(entry)
        }
    }

    /**
     * Imports the image at [imageUrl] (cancellable), then attaches it (or else [recipePhoto]) and
     * saves the recipe, which run to completion once started. Without a usable image the recipe
     * is saved without one.
     */
    private suspend fun saveWithImage(
        parsed: ParsedRecipe,
        recipeId: String,
        sourceUrl: String?,
        imageUrl: String?,
        recipePhoto: ParseAttachment.Image? = null,
        onSaving: suspend () -> Unit = {}
    ): Recipe {
        val image = imageUrl?.let { imageStore.importFromUrl(it) }
        coroutineContext.ensureActive()
        onSaving()
        return withContext(NonCancellable) {
            val localImage = image?.uri ?: recipePhoto?.let { savePhoto(it) }
            val storedImageUrl = localImage?.let { imageStore.attach(it, recipeId = recipeId) }
            buildRecipe(parsed, sourceUrl, storedImageUrl, image?.sourceUrl ?: imageUrl, recipeId)
                .also { recipeRepository.saveRecipe(it) }
        }
    }

    /** Saves [photo] on the device; null (logged) if it can't be, since a recipe doesn't need an image. */
    private suspend fun savePhoto(photo: ParseAttachment.Image): String? = try {
        val extension = when (photo.mediaType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> ".jpg"
        }
        imageStore.saveImage(ByteArrayInputStream(photo.bytes), extension)
            ?: null.also { ErrorLog.w(TAG, "The recipe photo isn't a usable image; saving the recipe without it") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.w(TAG, "Couldn't save the recipe photo; saving the recipe without it", e)
        null
    }

    /** What was sent instead of text, for the import debugging screen (which doesn't keep the files). */
    private fun describeAttachments(attachments: List<ParseAttachment>): String =
        attachments.joinToString(separator = "\n", prefix = "[Imported from ${attachments.size} file(s), not stored]\n") {
            "${it.mediaType}, ${it.bytes.size / BYTES_PER_KB} KB"
        }

    private fun buildRecipe(
        parsed: ParsedRecipe,
        sourceUrl: String?,
        storedImageUrl: String?,
        sourceImageUrl: String?,
        id: String = UUID.randomUUID().toString()
    ): Recipe {
        val now = Clock.System.now()
        return Recipe(
            id = id,
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
            imagePrompt = parsed.imagePrompt,
            imageUrl = storedImageUrl,
            sourceImageUrl = sourceImageUrl,
            createdAt = now,
            updatedAt = now
        )
    }

    private companion object {
        const val TAG = "ParseHtmlUseCase"
        const val BYTES_PER_KB = 1024
    }
}
