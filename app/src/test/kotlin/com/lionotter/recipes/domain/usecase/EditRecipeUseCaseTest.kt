package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.ImageChange
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.parsedRecipe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class EditRecipeUseCaseTest {
    private val created = Instant.parse("2024-01-01T00:00:00Z")

    private val existing = Recipe(
        id = "recipe-1",
        name = "Old Bread",
        sourceUrl = "https://example.com/bread",
        story = "Old story",
        instructionSections = listOf(
            InstructionSection(
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Mix.",
                        ingredients = listOf(Ingredient("special flour", amount = Amount(1.0, "cup"), density = 0.6))
                    )
                )
            )
        ),
        tags = listOf("old"),
        imageUrl = "gs://bucket/old.jpg",
        sourceImageUrl = "https://example.com/old.jpg",
        createdAt = created,
        updatedAt = created,
        isFavorite = true,
        userNotes = "Use less sugar"
    )

    private val parser = FakeRecipeParser()
    private val repository = FakeRecipeRepository(existing)
    private val settings = FakeAiSettings()
    private val imageStore = FakeImageStore()

    private val useCase = EditRecipeUseCase(
        ParseHtmlUseCase(parser, FakePageContentExtractor(), repository, FakeImportDebugLog(), settings, imageStore),
        repository,
        imageStore
    )

    private fun Outcome<Recipe>.value(): Recipe =
        (this as? Outcome.Success)?.value ?: throw AssertionError("Expected success, got $this")

    @Test
    fun `editing in place replaces only the AI content`() = runTest {
        val progress = mutableListOf<EditRecipeUseCase.EditProgress>()
        val edited = useCase.execute(
            recipeId = "recipe-1",
            markdownText = "# Banana Bread",
            model = AiModels.MODEL_HAIKU,
            thinkingEnabled = true,
            aiInstructions = "make it vegan",
            onProgress = { progress += it }
        ).value()

        val parsed = parsedRecipe()
        assertEquals("recipe-1", edited.id)
        assertEquals(parsed.name, edited.name)
        assertEquals(parsed.story, edited.story)
        assertEquals(parsed.instructionSections, edited.instructionSections)
        assertEquals(parsed.tags, edited.tags)
        assertEquals(existing.imageUrl, edited.imageUrl)
        assertEquals(existing.sourceImageUrl, edited.sourceImageUrl)
        assertEquals(existing.sourceUrl, edited.sourceUrl)
        assertEquals(created, edited.createdAt)
        assertTrue(edited.updatedAt > created)
        assertTrue(edited.isFavorite)
        assertEquals("Use less sugar", edited.userNotes)
        assertEquals(edited, repository.recipes.value["recipe-1"])
        assertTrue(imageStore.downloaded.isEmpty())
        assertEquals(EditRecipeUseCase.EditProgress.SavingRecipe, progress.last())

        val request = parser.requests.single()
        assertEquals("# Banana Bread", request.content)
        assertEquals(AiModels.MODEL_HAIKU, request.model)
        assertTrue(request.thinkingEnabled)
        assertEquals("make it vegan", request.aiInstructions)
        assertEquals(mapOf("special flour" to 0.6), request.densityOverrides)
    }

    @Test
    fun `tags from the edit screen are kept instead of the AI's`() = runTest {
        // In place, the recipe's current tags win, including a change made while the AI ran
        parser.onParse = { repository.setTags("recipe-1", listOf("Changed meanwhile")) }
        val edited = useCase.execute("recipe-1", "# Banana Bread", tags = listOf("old")).value()
        assertEquals(listOf("Changed meanwhile"), edited.tags)
        parser.onParse = {}

        val copy = useCase.execute(
            recipeId = "recipe-1",
            markdownText = "# Banana Bread",
            saveAsCopy = true,
            copyRecipeId = "copy-1",
            tags = emptyList()
        ).value()
        assertEquals(emptyList<String>(), copy.tags)
    }

    @Test
    fun `an image changed while the AI runs is kept`() = runTest {
        parser.onParse = { repository.setImageUrl("recipe-1", "gs://bucket/new.jpg") }
        val edited = useCase.execute("recipe-1", "# Banana Bread").value()
        assertEquals("gs://bucket/new.jpg", edited.imageUrl)
    }

    @Test
    fun `save as copy creates a new recipe and leaves the original alone`() = runTest {
        val copy = useCase.execute(
            recipeId = "recipe-1",
            markdownText = "# Banana Bread",
            saveAsCopy = true,
            copyRecipeId = "copy-1"
        ).value()

        assertEquals("copy-1", copy.id)
        assertEquals("Banana Bread", copy.name)
        assertFalse(copy.isFavorite)
        assertEquals(existing.imageUrl, copy.imageUrl)
        assertEquals(existing.sourceUrl, copy.sourceUrl)
        assertNotEquals(created, copy.createdAt)
        assertEquals(existing, repository.recipes.value["recipe-1"])
        assertEquals(copy, repository.recipes.value["copy-1"])
    }

    @Test
    fun `save as copy with a new image stores it for the copy only`() = runTest {
        val copy = useCase.execute(
            recipeId = "recipe-1",
            markdownText = "# Banana Bread",
            saveAsCopy = true,
            copyRecipeId = "copy-1",
            copyImage = ImageChange.Replace("file:///images/picked.jpg")
        ).value()

        assertEquals("gs://bucket/picked.jpg", copy.imageUrl)
        assertEquals(listOf("file:///images/picked.jpg"), imageStore.stored)
        assertEquals("gs://bucket/old.jpg", repository.recipes.value.getValue("recipe-1").imageUrl)
    }

    @Test
    fun `save as copy can drop the image`() = runTest {
        val copy = useCase.execute(
            recipeId = "recipe-1",
            markdownText = "# Banana Bread",
            saveAsCopy = true,
            copyRecipeId = "copy-1",
            copyImage = ImageChange.Remove
        ).value()

        assertEquals(null, copy.imageUrl)
        assertEquals("gs://bucket/old.jpg", repository.recipes.value.getValue("recipe-1").imageUrl)
    }

    @Test
    fun `a copy image is not stored when the AI fails`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))
        useCase.execute(
            recipeId = "recipe-1",
            markdownText = "# Banana Bread",
            saveAsCopy = true,
            copyImage = ImageChange.Replace("file:///images/picked.jpg")
        )
        assertEquals(emptyList<String>(), imageStore.stored)
    }

    @Test
    fun `editing works at the recipe limit`() = runTest {
        repository.countOverride = ResourceLimits.MAX_RECIPES
        useCase.execute("recipe-1", "# Banana Bread").value()
    }

    @Test
    fun `a missing recipe fails with RecipeNotFound`() = runTest {
        assertEquals(Outcome.Failure(RecipeError.RecipeNotFound), useCase.execute("missing", "text"))
        assertTrue(parser.requests.isEmpty())
    }

    @Test
    fun `fails with NoApiKey and saves nothing`() = runTest {
        settings.apiKeyFlow.value = null
        assertEquals(Outcome.Failure(RecipeError.NoApiKey), useCase.execute("recipe-1", "text"))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `AI failures leave the recipe unchanged`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))
        assertEquals(
            Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401)),
            useCase.execute("recipe-1", "text")
        )
        assertTrue(repository.saved.isEmpty())
        assertEquals(existing, repository.recipes.value["recipe-1"])
    }
}
