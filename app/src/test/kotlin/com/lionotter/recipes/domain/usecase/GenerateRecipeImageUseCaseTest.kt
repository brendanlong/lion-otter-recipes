package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
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
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )
    private val repository = FakeRecipeRepository(recipe)
    private val imageStore = FakeImageStore()
    private val generator = FakeRecipeImageGenerator()
    private val settings = FakeAiSettings(openAiApiKey = "sk-openai")
    private val useCase = GenerateRecipeImageUseCase(generator, repository, imageStore, settings)

    @Test
    fun `generates the image and makes it the recipe's image`() = runTest {
        val result = useCase.execute("r1", "A loaf of banana bread")

        assertEquals(listOf("A loaf of banana bread" to "sk-openai"), generator.prompts)
        assertEquals(listOf(FakeRecipeImageGenerator.GENERATED_IMAGE_SIZE), imageStore.savedStreams)
        val imageUrl = repository.recipes.value.getValue("r1").imageUrl
        assertEquals("gs://bucket/stream.jpg", imageUrl)
        assertEquals(Outcome.Success(GenerateRecipeImageUseCase.Result.Generated("gs://bucket/stream.jpg")), result)
    }

    @Test
    fun `skips without a key, with generation off, or when the recipe no longer needs an image`() = runTest {
        settings.openAiApiKeyFlow.value = null
        assertEquals(Outcome.Success(GenerateRecipeImageUseCase.Result.Skipped), useCase.execute("r1", "prompt"))

        settings.openAiApiKeyFlow.value = "sk-openai"
        settings.imageGenerationFlow.value = false
        assertEquals(Outcome.Success(GenerateRecipeImageUseCase.Result.Skipped), useCase.execute("r1", "prompt"))

        settings.imageGenerationFlow.value = true
        assertEquals(Outcome.Success(GenerateRecipeImageUseCase.Result.Skipped), useCase.execute("gone", "prompt"))

        repository.setImageUrl("r1", "gs://bucket/mine.jpg")
        assertEquals(Outcome.Success(GenerateRecipeImageUseCase.Result.Skipped), useCase.execute("r1", "prompt"))

        assertTrue(generator.prompts.isEmpty())
    }

    @Test
    fun `an image the user added meanwhile wins and the generated one is discarded`() = runTest {
        generator.onGenerate = { repository.setImageUrl("r1", "gs://bucket/mine.jpg") }

        val result = useCase.execute("r1", "prompt")

        assertEquals(Outcome.Success(GenerateRecipeImageUseCase.Result.Skipped), result)
        assertEquals("gs://bucket/mine.jpg", repository.recipes.value.getValue("r1").imageUrl)
        assertEquals(listOf("file:///images/stream.jpg"), imageStore.deletedLocal)
        assertTrue(imageStore.stored.isEmpty())
    }

    @Test
    fun `an image the user added during the upload wins and the generated one is released`() = runTest {
        val uploading = object : ImageStore by imageStore {
            override suspend fun attach(localUri: String, recipeId: String?, previousImageUrl: String?): String {
                repository.setImageUrl("r1", "gs://bucket/mine.jpg")
                return imageStore.attach(localUri, recipeId, previousImageUrl)
            }
        }
        val useCase = GenerateRecipeImageUseCase(generator, repository, uploading, settings)

        val result = useCase.execute("r1", "prompt")

        assertEquals(Outcome.Success(GenerateRecipeImageUseCase.Result.Skipped), result)
        assertEquals("gs://bucket/mine.jpg", repository.recipes.value.getValue("r1").imageUrl)
        assertEquals(listOf("gs://bucket/stream.jpg" to "r1"), imageStore.released)
    }

    @Test
    fun `the generated image is discarded when attaching it fails`() = runTest {
        imageStore.attachError = IOException("disk full")

        val thrown = runCatching { useCase.execute("r1", "prompt") }.exceptionOrNull()

        assertEquals("disk full", (thrown as IOException).message)
        assertEquals(listOf("file:///images/stream.jpg"), imageStore.deletedLocal)
    }

    @Test
    fun `generation failures are returned and the recipe is unchanged`() = runTest {
        val failure = Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429))
        generator.result = failure

        assertEquals(failure, useCase.execute("r1", "prompt"))
        assertNull(repository.recipes.value.getValue("r1").imageUrl)
        assertTrue(imageStore.savedStreams.isEmpty())
    }
}
