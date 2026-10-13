package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeRecipeImageGenerator
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Instant

class GenerateRecipeImageUseCaseTest {
    private val recipe = Recipe(
        id = "r1",
        name = "Banana Bread",
        imagePrompt = "A loaf of banana bread",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )
    private val repository = FakeRecipeRepository(recipe)
    private val imageStore = FakeImageStore()
    private val generator = FakeRecipeImageGenerator()
    private val settings = FakeAiSettings(openAiApiKey = "sk-openai")

    private fun useCase(store: ImageStore = imageStore) = GenerateRecipeImageUseCase(
        generator,
        repository,
        store,
        settings
    )

    private fun imageUrl() = repository.recipes.value.getValue("r1").imageUrl

    @Test
    fun `generates an image from the saved prompt and makes it the recipe's image`() = runTest {
        val result = useCase().execute("r1", prompt = null, replaceImage = false)

        assertEquals(listOf(Triple("A loaf of banana bread", AiProviders.GPT_IMAGE_FLARE, "sk-openai")), generator.prompts)
        assertEquals(listOf(FakeRecipeImageGenerator.GENERATED_IMAGE_SIZE), imageStore.savedStreams)
        assertEquals("gs://bucket/stream.jpg", imageUrl())
        assertEquals(GenerateRecipeImageUseCase.Result.Generated("gs://bucket/stream.jpg"), result)
    }

    @Test
    fun `a given prompt wins over the saved one`() = runTest {
        useCase().execute("r1", prompt = "Pancakes", replaceImage = false)

        assertEquals("Pancakes", generator.prompts.single().first)
    }

    @Test
    fun `without a saved prompt, one is made from the recipe`() = runTest {
        repository.setImagePrompt("r1", null)

        useCase().execute("r1", prompt = null, replaceImage = false)

        assertEquals(GenerateRecipeImageUseCase.fallbackPrompt(recipe), generator.prompts.single().first)
        assertTrue(generator.prompts.single().first.contains("Banana Bread"))
    }

    @Test
    fun `image failures are returned and the recipe is unchanged`() = runTest {
        generator.result = Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429))

        assertEquals(
            GenerateRecipeImageUseCase.Result.ImageFailed(RecipeError.Ai(AiFailure.RATE_LIMITED, 429)),
            useCase().execute("r1", "prompt", false)
        )
        assertNull(imageUrl())
        assertTrue(imageStore.savedStreams.isEmpty())
    }

    @Test
    fun `replaces an existing image only when asked, then releases the old one`() = runTest {
        repository.setImageUrl("r1", "gs://bucket/old.jpg")

        assertEquals(GenerateRecipeImageUseCase.Result.Skipped, useCase().execute("r1", null, replaceImage = false))
        assertTrue(generator.prompts.isEmpty())

        useCase().execute("r1", null, replaceImage = true)

        assertEquals("gs://bucket/stream.jpg", imageUrl())
        assertEquals(listOf("gs://bucket/old.jpg" to "r1"), imageStore.released)
    }

    @Test
    fun `turning image generation off skips missing images but not ones the user asked for`() = runTest {
        settings.imageGenerationFlow.value = false

        assertEquals(GenerateRecipeImageUseCase.Result.Skipped, useCase().execute("r1", null, replaceImage = false))
        assertTrue(generator.prompts.isEmpty())

        useCase().execute("r1", "prompt", replaceImage = true)
        assertEquals("gs://bucket/stream.jpg", imageUrl())
    }

    @Test
    fun `skips without an OpenAI key or a recipe`() = runTest {
        assertEquals(GenerateRecipeImageUseCase.Result.Skipped, useCase().execute("gone", "prompt", false))
        settings.openAiApiKeyFlow.value = null
        assertEquals(GenerateRecipeImageUseCase.Result.Skipped, useCase().execute("r1", "prompt", false))
        assertTrue(generator.prompts.isEmpty())
    }

    @Test
    fun `an image the user set during generation wins and the generated one is discarded`() = runTest {
        generator.onGenerate = { repository.setImageUrl("r1", "gs://bucket/mine.jpg") }

        val result = useCase().execute("r1", "prompt", replaceImage = false)

        assertEquals(GenerateRecipeImageUseCase.Result.Skipped, result)
        assertEquals("gs://bucket/mine.jpg", imageUrl())
        assertEquals(listOf("file:///images/stream.jpg"), imageStore.deletedLocal)
        assertTrue(imageStore.stored.isEmpty())
    }

    @Test
    fun `an image the user set during the upload wins and the generated one is released`() = runTest {
        val uploading = object : ImageStore by imageStore {
            override suspend fun attach(localUri: String, recipeId: String?, previousImageUrl: String?): String {
                repository.setImageUrl("r1", "gs://bucket/mine.jpg")
                return imageStore.attach(localUri, recipeId, previousImageUrl)
            }
        }

        val result = useCase(uploading).execute("r1", "prompt", replaceImage = true)

        assertEquals(GenerateRecipeImageUseCase.Result.Skipped, result)
        assertEquals("gs://bucket/mine.jpg", imageUrl())
        assertEquals(listOf("gs://bucket/stream.jpg" to "r1"), imageStore.released)
    }

    @Test
    fun `the generated image is discarded when attaching it fails`() = runTest {
        imageStore.attachError = IOException("disk full")

        val thrown = runCatching { useCase().execute("r1", "prompt", false) }.exceptionOrNull()

        assertEquals("disk full", (thrown as IOException).message)
        assertEquals(listOf("file:///images/stream.jpg"), imageStore.deletedLocal)
    }
}
