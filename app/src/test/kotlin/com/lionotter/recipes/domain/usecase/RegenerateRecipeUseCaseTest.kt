package com.lionotter.recipes.domain.usecase

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
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.parsedRecipe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class RegenerateRecipeUseCaseTest {
    private val created = Instant.parse("2024-01-01T00:00:00Z")
    private val sourceUrl = "https://example.com/bread"

    private val existing = Recipe(
        id = "recipe-1",
        name = "Old Bread",
        sourceUrl = sourceUrl,
        imageUrl = "gs://bucket/mine.jpg",
        sourceImageUrl = "https://example.com/old.jpg",
        createdAt = created,
        updatedAt = created,
        isFavorite = true,
        userNotes = "notes"
    )

    private val page = FetchedPage(html = "<html>bread</html>", imageUrl = "http://example.com/new.jpg")

    private val parser = FakeRecipeParser()
    private val repository = FakeRecipeRepository(existing)
    private val imageStore = FakeImageStore()
    private val settings = FakeAiSettings()

    private val fetcher = FakePageFetcher(mapOf(sourceUrl to Outcome.Success(page)))

    private fun useCase(pageResult: Outcome<FetchedPage> = Outcome.Success(page)) = RegenerateRecipeUseCase(
        ParseHtmlUseCase(parser, FakePageContentExtractor(), repository, FakeImportDebugLog(), settings, imageStore, FakeRecipeImageScheduler()),
        repository,
        if (pageResult == Outcome.Success(page)) fetcher else FakePageFetcher(mapOf(sourceUrl to pageResult)),
        FakePageContentExtractor(imageUrl = "https://example.com/captured.jpg"),
        imageStore
    )

    private fun Outcome<Recipe>.value(): Recipe =
        (this as? Outcome.Success)?.value ?: throw AssertionError("Expected success, got $this")

    @Test
    fun `regenerating keeps the image and everything the AI doesn't own`() = runTest {
        val progress = mutableListOf<RegenerateRecipeUseCase.RegenerateProgress>()
        val regenerated = useCase().execute("recipe-1") { progress += it }.value()

        assertEquals("recipe-1", regenerated.id)
        assertEquals(parsedRecipe().name, regenerated.name)
        assertEquals(parsedRecipe().instructionSections, regenerated.instructionSections)
        assertEquals("gs://bucket/mine.jpg", regenerated.imageUrl)
        assertEquals("http://example.com/new.jpg", regenerated.sourceImageUrl)
        assertEquals(sourceUrl, regenerated.sourceUrl)
        assertEquals(created, regenerated.createdAt)
        assertTrue(regenerated.isFavorite)
        assertEquals("notes", regenerated.userNotes)
        assertTrue(imageStore.downloaded.isEmpty())
        assertEquals(regenerated, repository.recipes.value["recipe-1"])
        assertEquals("extracted(<html>bread</html>)", parser.requests.single().content)
        assertEquals(
            listOf(
                RegenerateRecipeUseCase.RegenerateProgress.FetchingFromUrl,
                RegenerateRecipeUseCase.RegenerateProgress.ParsingRecipe,
                RegenerateRecipeUseCase.RegenerateProgress.RecipeNameAvailable("Banana Bread"),
                RegenerateRecipeUseCase.RegenerateProgress.SavingRecipe
            ),
            progress
        )
    }

    @Test
    fun `a recipe without an image gets the page image`() = runTest {
        repository.setImageUrl("recipe-1", null)
        val regenerated = useCase().execute("recipe-1").value()
        assertEquals(listOf("http://example.com/new.jpg"), imageStore.downloaded)
        assertEquals("gs://bucket/new.jpg", regenerated.imageUrl)
        assertEquals("https://example.com/new.jpg", regenerated.sourceImageUrl)
    }

    @Test
    fun `a missing recipe fails with RecipeNotFound`() = runTest {
        assertEquals(Outcome.Failure(RecipeError.RecipeNotFound), useCase().execute("missing"))
    }

    @Test
    fun `a recipe without a source URL can't be regenerated`() = runTest {
        repository.updateTitleAndUrl("recipe-1", "Old Bread", null)
        assertEquals(Outcome.Failure(RecipeError.NoSourceUrl), useCase().execute("recipe-1"))
        assertTrue(parser.requests.isEmpty())
    }

    @Test
    fun `a page that can't be fetched fails without calling the AI`() = runTest {
        val notFound = Outcome.Failure(RecipeError.PageFetch(PageFetchFailure.NOT_FOUND, 404))
        assertEquals(notFound, useCase(notFound).execute("recipe-1"))
        assertTrue(parser.requests.isEmpty())
    }

    @Test
    fun `a page that isn't a recipe leaves the recipe unchanged`() = runTest {
        parser.result = Outcome.Failure(RecipeError.NotARecipe("Access denied page"))
        assertEquals(Outcome.Failure(RecipeError.NotARecipe("Access denied page")), useCase().execute("recipe-1"))
        assertEquals(existing, repository.recipes.value["recipe-1"])
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a page captured in the browser is used instead of downloading it`() = runTest {
        repository.setImageUrl("recipe-1", null)
        val progress = mutableListOf<RegenerateRecipeUseCase.RegenerateProgress>()

        val regenerated = useCase().execute("recipe-1", pageHtml = "<html>captured</html>") { progress += it }.value()

        assertTrue(fetcher.fetched.isEmpty())
        assertEquals("extracted(<html>captured</html>)", parser.requests.single().content)
        // The image comes from the captured page, as it would from a downloaded one
        assertEquals(listOf("https://example.com/captured.jpg"), imageStore.downloaded)
        assertEquals(sourceUrl, regenerated.sourceUrl)
        assertTrue(RegenerateRecipeUseCase.RegenerateProgress.FetchingFromUrl !in progress)
    }
}
