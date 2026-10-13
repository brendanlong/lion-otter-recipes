package com.lionotter.recipes.worker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.GenerateMissingImagesUseCase
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.domain.usecase.WriteImagePromptUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImagePromptWriter
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeRecipeImageGenerator
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import javax.inject.Provider
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class MissingImagesWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun recipe(id: String, imageUrl: String? = null) = Recipe(
        id = id,
        name = id,
        imageUrl = imageUrl,
        imagePrompt = "A picture of $id",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    private val repository = FakeRecipeRepository(recipe("a"), recipe("b", "gs://bucket/b.jpg"), recipe("c"))
    private val generator = FakeRecipeImageGenerator()
    private val settings = FakeAiSettings(openAiApiKey = "sk-openai")
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun worker(): MissingImagesWorker =
        TestListenableWorkerBuilder<MissingImagesWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = MissingImagesWorker(
                    appContext,
                    workerParameters,
                    GenerateMissingImagesUseCase(repository, FakeRecipeImageScheduler()),
                    GenerateRecipeImageUseCase(
                        generator,
                        WriteImagePromptUseCase(FakeImagePromptWriter(), settings),
                        repository,
                        FakeImageStore(),
                        settings
                    ),
                    RecipeNotificationHelper(appContext),
                    RunningAccountWork(Provider { error("Not used by this test") })
                )
            })
            .build()

    private fun imageUrl(id: String) = repository.recipes.value.getValue(id).imageUrl

    @Test
    fun `generates an image for each recipe without one`() = runTest {
        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("A picture of a", "A picture of c"), generator.prompts.map { it.first })
        assertEquals("gs://bucket/stream.jpg", imageUrl("a"))
        assertEquals("gs://bucket/b.jpg", imageUrl("b"))
        assertEquals("gs://bucket/stream.jpg", imageUrl("c"))
    }

    @Test
    fun `a key problem stops after the first recipe`() = runTest {
        generator.result = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))

        assertTrue(worker().doWork() is ListenableWorker.Result.Success)

        assertEquals(1, generator.prompts.size)
        val text = notifications.allNotifications.single().extras.getCharSequence("android.text").toString()
        assertEquals(context.getString(R.string.image_generation_invalid_key), text)
    }

    @Test
    fun `other failures are reported once at the end`() = runTest {
        generator.result = Outcome.Failure(RecipeError.Ai(AiFailure.REFUSED))

        worker().doWork()

        assertEquals(2, generator.prompts.size)
        val text = notifications.allNotifications.single().extras.getCharSequence("android.text").toString()
        assertEquals(context.resources.getQuantityString(R.plurals.missing_images_failed, 2, 2), text)
    }

    @Test
    fun `turning image generation off stops it`() = runTest {
        settings.imageGenerationFlow.value = false

        worker().doWork()

        assertTrue(generator.prompts.isEmpty())
        assertNull(imageUrl("a"))
    }
}
