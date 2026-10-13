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

    private fun useCase(extractor: FakePageContentExtractor = FakePageContentExtractor()) =
        ParseHtmlUseCase(parser, extractor, repository, debugLog, settings, imageStore)

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
}
