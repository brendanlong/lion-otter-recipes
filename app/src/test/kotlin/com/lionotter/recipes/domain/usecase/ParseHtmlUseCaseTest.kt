package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.parsedRecipe
import com.lionotter.recipes.testutil.parsedWithUsage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseHtmlUseCaseTest {
    private val parser = FakeRecipeParser()
    private val repository = FakeRecipeRepository()
    private val debugLog = FakeImportDebugLog()
    private val settings = FakeAiSettings()
    private val imageStore = FakeImageStore(failingUrls = setOf("https://example.com/broken.jpg"))
    private val imageScheduler = FakeRecipeImageScheduler()

    private fun useCase(extractor: FakePageContentExtractor = FakePageContentExtractor()) =
        ParseHtmlUseCase(parser, extractor, repository, debugLog, settings, imageStore, imageScheduler)

    private fun Outcome<Recipe>.value(): Recipe =
        (this as? Outcome.Success)?.value ?: throw AssertionError("Expected success, got $this")

    @Test
    fun `parses, downloads the image and saves the recipe`() = runTest {
        val progress = mutableListOf<ParseHtmlUseCase.ParseProgress>()
        val recipe = useCase().parseText(
            text = "recipe text",
            sourceUrl = "https://example.com/bread",
            imageUrl = "http://example.com/bread.jpg",
            onProgress = { progress += it }
        ).value()

        assertEquals("Banana Bread", recipe.name)
        assertEquals("https://example.com/bread", recipe.sourceUrl)
        assertEquals(parsedRecipe().instructionSections, recipe.instructionSections)
        assertEquals(listOf("loaf pan"), recipe.equipment)
        assertEquals("gs://bucket/bread.jpg", recipe.imageUrl)
        assertEquals("https://example.com/bread.jpg", recipe.sourceImageUrl)
        assertEquals(listOf(recipe), repository.saved)
        assertEquals(
            listOf(
                ParseHtmlUseCase.ParseProgress.ParsingRecipe,
                ParseHtmlUseCase.ParseProgress.RecipeNameAvailable("Banana Bread"),
                ParseHtmlUseCase.ParseProgress.SavingRecipe
            ),
            progress
        )
        val request = parser.requests.single()
        assertEquals("recipe text", request.content)
        assertEquals("sk-ant-test", request.apiKey)
        assertEquals(AiModels.DEFAULT_MODEL, request.model)
        assertFalse(request.thinkingEnabled)
    }

    @Test
    fun `fails with NoApiKey without calling the AI`() = runTest {
        settings.apiKeyFlow.value = null
        assertEquals(Outcome.Failure(RecipeError.NoApiKey), useCase().parseText("recipe text"))
        settings.apiKeyFlow.value = "  "
        assertEquals(Outcome.Failure(RecipeError.NoApiKey), useCase().parseText("recipe text"))
        assertTrue(parser.requests.isEmpty())
    }

    @Test
    fun `stops at the recipe limit before spending AI credits`() = runTest {
        repository.countOverride = ResourceLimits.MAX_RECIPES
        assertEquals(
            Outcome.Failure(RecipeError.LimitReached(ResourceLimits.MAX_RECIPES, ResourceLimits.MAX_RECIPES)),
            useCase().parseText("recipe text")
        )
        assertTrue(parser.requests.isEmpty())
    }

    @Test
    fun `the recipe limit doesn't apply when not saving`() = runTest {
        repository.countOverride = ResourceLimits.MAX_RECIPES
        useCase().parseText("recipe text", saveRecipe = false).value()
    }

    @Test
    fun `AI errors are returned and nothing is saved`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429))
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429)),
            useCase().parseText("recipe text", imageUrl = "https://example.com/bread.jpg")
        )
        assertTrue(repository.saved.isEmpty())
        assertTrue(imageStore.downloaded.isEmpty())
    }

    @Test
    fun `content that isn't a recipe fails with the AI's explanation`() = runTest {
        parser.result = Outcome.Failure(RecipeError.NotARecipe("This is a blog post"))
        assertEquals(Outcome.Failure(RecipeError.NotARecipe("This is a blog post")), useCase().parseText("text"))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `stored model is normalized and overrides win`() = runTest {
        settings.modelFlow.value = "claude-haiku-4-5-20251001"
        settings.thinkingFlow.value = true
        useCase().parseText("text")
        assertEquals(AiModels.MODEL_HAIKU, parser.requests.last().model)
        assertTrue(parser.requests.last().thinkingEnabled)

        useCase().parseText(
            "text",
            model = AiModels.MODEL_OPUS,
            thinkingEnabled = false,
            densityOverrides = mapOf("flour" to 0.5),
            aiInstructions = "make it vegan"
        )
        val request = parser.requests.last()
        assertEquals(AiModels.MODEL_OPUS, request.model)
        assertFalse(request.thinkingEnabled)
        assertEquals(mapOf("flour" to 0.5), request.densityOverrides)
        assertEquals("make it vegan", request.aiInstructions)
    }

    @Test
    fun `without saving, no image is downloaded and the page image is passed back`() = runTest {
        val recipe = useCase().parseText(
            "text",
            imageUrl = "https://example.com/bread.jpg",
            saveRecipe = false
        ).value()
        assertNull(recipe.imageUrl)
        assertEquals("https://example.com/bread.jpg", recipe.sourceImageUrl)
        assertTrue(imageStore.downloaded.isEmpty())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a failed image download still saves the recipe`() = runTest {
        val recipe = useCase().parseText("text", imageUrl = "https://example.com/broken.jpg").value()
        assertNull(recipe.imageUrl)
        assertEquals("https://example.com/broken.jpg", recipe.sourceImageUrl)
        assertEquals(listOf(recipe), repository.saved)
        assertTrue(imageStore.stored.isEmpty())
    }

    @Test
    fun `parseHtml sends the extracted content and falls back to its image`() = runTest {
        val extractor = FakePageContentExtractor(imageUrl = "https://example.com/ld.jpg")
        val recipe = useCase(extractor).parseHtml("<html/>", sourceUrl = "https://example.com/bread").value()
        assertEquals("extracted(<html/>)", parser.requests.single().content)
        assertEquals("https://example.com/ld.jpg", recipe.sourceImageUrl)
        assertEquals(listOf("https://example.com/ld.jpg"), imageStore.downloaded)

        useCase(extractor).parseHtml("<html/>", imageUrl = "https://example.com/og.jpg")
        assertEquals("https://example.com/og.jpg", imageStore.downloaded.last())
    }

    @Test
    fun `import debugging records successes and failures`() = runTest {
        settings.debuggingFlow.value = true
        val recipe = useCase().parseHtml("<html/>", sourceUrl = "https://example.com/bread").value()
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.TRUNCATED))
        useCase().parseText("text")

        val (success, failure) = debugLog.entries
        assertFalse(success.isError)
        assertEquals("<html/>", success.originalHtml)
        assertEquals("extracted(<html/>)", success.cleanedContent)
        assertEquals(recipe.id, success.recipeId)
        assertEquals(100L, success.inputTokens)
        assertEquals(AiModels.DEFAULT_MODEL, success.aiModel)
        assertTrue(failure.isError)
        assertNull(failure.recipeId)
        assertEquals("AI request failed: TRUNCATED", failure.errorMessage)
    }

    @Test
    fun `nothing is recorded when import debugging is off`() = runTest {
        useCase().parseText("text")
        assertTrue(debugLog.entries.isEmpty())
    }

    @Test
    fun `batch results are saved under the given ID, overwriting at the limit`() = runTest {
        settings.debuggingFlow.value = true
        val saved = useCase().saveBatchResult(
            parsedWithUsage = parsedWithUsage(parsedRecipe("Scones")),
            recipeId = "batch-recipe",
            imageUrl = "https://example.com/scones.jpg",
            model = AiModels.MODEL_SONNET,
            thinkingEnabled = false
        ).value()
        assertEquals("batch-recipe", saved.id)
        assertEquals("Scones", saved.name)
        assertEquals("gs://bucket/scones.jpg", saved.imageUrl)
        assertTrue(debugLog.entries.single().batchMode)

        repository.countOverride = ResourceLimits.MAX_RECIPES
        val again = useCase().saveBatchResult(
            parsedWithUsage(parsedRecipe("Scones")), "batch-recipe", model = AiModels.MODEL_SONNET, thinkingEnabled = false
        )
        assertTrue(again is Outcome.Success)
        assertEquals(
            Outcome.Failure(RecipeError.LimitReached(ResourceLimits.MAX_RECIPES, ResourceLimits.MAX_RECIPES)),
            useCase().saveBatchResult(
                parsedWithUsage(), "new-recipe", model = AiModels.MODEL_SONNET, thinkingEnabled = false
            )
        )
    }

    @Test
    fun `asks the AI to reuse the user's most used tags`() = runTest {
        repository.saveRecipe(tagged("a", "Dessert", "Baking"))
        repository.saveRecipe(tagged("b", " dessert", "Quick"))

        useCase().parseText("recipe text")

        assertEquals(listOf("Dessert", "Baking", "Quick"), parser.requests.single().existingTags)
    }

    @Test
    fun `parses without tag hints when the tags can't be read`() = runTest {
        repository.tagsError = java.io.IOException("offline")

        useCase().parseText("recipe text").value()

        assertEquals(emptyList<String>(), parser.requests.single().existingTags)
    }

    private fun enableImageGeneration() {
        settings.openAiApiKeyFlow.value = "sk-openai"
    }

    @Test
    fun `the AI's image prompt is saved with the recipe`() = runTest {
        parser.result = Outcome.Success(parsedWithUsage(parsedRecipe().copy(imagePrompt = "A loaf of banana bread")))

        val recipe = useCase().parseText("recipe text").value()

        assertEquals("A loaf of banana bread", recipe.imagePrompt)
        assertEquals("A loaf of banana bread", repository.saved.single().imagePrompt)
    }

    @Test
    fun `a recipe saved without an image gets one generated`() = runTest {
        enableImageGeneration()

        val recipe = useCase().parseText("recipe text").value()

        assertEquals(listOf(FakeRecipeImageScheduler.Scheduled(recipe.id)), imageScheduler.scheduled)
    }

    @Test
    fun `an image is generated when the page image can't be downloaded`() = runTest {
        enableImageGeneration()

        val recipe = useCase().parseText("text", imageUrl = "https://example.com/broken.jpg").value()

        assertEquals(listOf(FakeRecipeImageScheduler.Scheduled(recipe.id)), imageScheduler.scheduled)
    }

    @Test
    fun `no image is generated for a recipe with an image, an unsaved parse, or without generation`() = runTest {
        enableImageGeneration()
        useCase().parseText("text", imageUrl = "https://example.com/bread.jpg").value()
        useCase().parseAttachments(
            listOf(ParseAttachment.Image(byteArrayOf(1), "image/jpeg")),
            recipePhoto = ParseAttachment.Image(byteArrayOf(1), "image/jpeg")
        ).value()
        useCase().parseText("text", saveRecipe = false).value()
        useCase().parseText("text", generateMissingImage = false).value()
        settings.imageGenerationFlow.value = false
        useCase().parseText("text").value()
        settings.imageGenerationFlow.value = true
        settings.openAiApiKeyFlow.value = null
        useCase().parseText("text").value()

        assertTrue(imageScheduler.scheduled.isEmpty())
    }

    @Test
    fun `a PDF import gets a generated image`() = runTest {
        enableImageGeneration()

        useCase().parseAttachments(listOf(ParseAttachment.Pdf(byteArrayOf(1)))).value()

        assertEquals(1, imageScheduler.scheduled.size)
    }

    @Test
    fun `a recipe that can't be scheduled for an image is still imported`() = runTest {
        enableImageGeneration()
        val failingScheduler = object : RecipeImageScheduler {
            override fun scheduleImageGeneration(recipeId: String, prompt: String?, replaceImage: Boolean) =
                throw IllegalStateException("WorkManager isn't initialized")

            override fun scheduleMissingImages(recipeIds: List<String>) = Unit
        }
        val useCase = ParseHtmlUseCase(parser, FakePageContentExtractor(), repository, debugLog, settings, imageStore, failingScheduler)

        val recipe = useCase.parseText("recipe text").value()

        assertEquals(listOf(recipe), repository.saved)
    }

    private fun tagged(id: String, vararg tags: String) = Recipe(
        id = id,
        name = id,
        tags = tags.toList(),
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0)
    )
}
