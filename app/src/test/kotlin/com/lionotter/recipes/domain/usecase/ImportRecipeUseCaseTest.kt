package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.FetchedPage
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakePageFetcher
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRecipeUseCaseTest {
    private val parser = FakeRecipeParser()
    private val repository = FakeRecipeRepository()
    private val settings = FakeAiSettings()
    private val imageStore = FakeImageStore()
    private val extractor = FakePageContentExtractor(title = "Banana Bread | Example")

    private val page = FetchedPage(html = "<html>bread</html>", imageUrl = "https://example.com/bread.jpg")
    private val blocked = Outcome.Failure(RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403))

    private fun useCase(
        vararg pages: Pair<String, Outcome<FetchedPage>>,
        extractor: FakePageContentExtractor = this.extractor
    ): ImportRecipeUseCase {
        val parseHtml = ParseHtmlUseCase(parser, extractor, repository, FakeImportDebugLog(), settings, imageStore)
        return ImportRecipeUseCase(FakePageFetcher(pages.toMap()), extractor, parseHtml)
    }

    private fun Outcome<Recipe>.value(): Recipe =
        (this as? Outcome.Success)?.value ?: throw AssertionError("Expected success, got $this")

    @Test
    fun `imports and saves the recipe, reporting progress`() = runTest {
        val progress = mutableListOf<ImportRecipeUseCase.ImportProgress>()
        val recipe = useCase("https://example.com/bread" to Outcome.Success(page))
            .execute("https://example.com/bread") { progress += it }
            .value()

        assertEquals("https://example.com/bread", recipe.sourceUrl)
        assertEquals("gs://bucket/bread.jpg", recipe.imageUrl)
        assertEquals(listOf(recipe), repository.saved)
        assertEquals("extracted(<html>bread</html>)", parser.requests.single().content)
        assertEquals(
            listOf(
                ImportRecipeUseCase.ImportProgress.FetchingPage,
                ImportRecipeUseCase.ImportProgress.PageMetadataAvailable(
                    "Banana Bread | Example",
                    "https://example.com/bread.jpg"
                ),
                ImportRecipeUseCase.ImportProgress.ParsingRecipe,
                ImportRecipeUseCase.ImportProgress.RecipeNameAvailable("Banana Bread"),
                ImportRecipeUseCase.ImportProgress.SavingRecipe
            ),
            progress
        )
    }

    @Test
    fun `a blocked page fails without calling the AI`() = runTest {
        val result = useCase("https://example.com/bread" to blocked).execute("https://example.com/bread")
        assertEquals(blocked, result)
        assertTrue(parser.requests.isEmpty())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `an http URL that fails is retried over https`() = runTest {
        val recipe = useCase(
            "http://example.com/bread" to Outcome.Failure(RecipeError.PageFetch(PageFetchFailure.NETWORK)),
            "https://example.com/bread" to Outcome.Success(page)
        ).execute("http://example.com/bread").value()
        assertEquals("https://example.com/bread", recipe.sourceUrl)
    }

    @Test
    fun `when the https retry also fails, the original error is returned`() = runTest {
        val httpError = Outcome.Failure(RecipeError.PageFetch(PageFetchFailure.NOT_FOUND, 404))
        val result = useCase(
            "http://example.com/bread" to httpError,
            "https://example.com/bread" to blocked
        ).execute("http://example.com/bread")
        assertEquals(httpError, result)
    }

    @Test
    fun `fails with NoApiKey when no key is set`() = runTest {
        settings.apiKeyFlow.value = null
        val result = useCase("https://example.com/bread" to Outcome.Success(page)).execute("https://example.com/bread")
        assertEquals(Outcome.Failure(RecipeError.NoApiKey), result)
        assertTrue(parser.requests.isEmpty())
    }

    @Test
    fun `AI failures are returned`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529))
        val result = useCase("https://example.com/bread" to Outcome.Success(page)).execute("https://example.com/bread")
        assertEquals(Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529)), result)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a page captured in the browser is imported without downloading it`() = runTest {
        val progress = mutableListOf<ImportRecipeUseCase.ImportProgress>()
        val capturedExtractor = FakePageContentExtractor(imageUrl = "https://example.com/og.jpg", title = "Bread")

        // No pages: FakePageFetcher fails the test if anything is downloaded
        val recipe = useCase(extractor = capturedExtractor)
            .execute("https://example.com/bread?final", pageHtml = "<html>rendered</html>") { progress += it }
            .value()

        assertEquals("https://example.com/bread?final", recipe.sourceUrl)
        assertEquals("extracted(<html>rendered</html>)", parser.requests.single().content)
        assertEquals("gs://bucket/og.jpg", recipe.imageUrl)
        assertEquals(
            ImportRecipeUseCase.ImportProgress.PageMetadataAvailable("Bread", "https://example.com/og.jpg"),
            progress.first()
        )
    }
}
