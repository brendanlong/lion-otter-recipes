package com.lionotter.recipes.worker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeRecipeImageGenerator
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import javax.inject.Provider
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class RecipeImageWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = FakeRecipeRepository(
        Recipe(
            id = "r1",
            name = "Banana Bread",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        ),
        Recipe(
            id = "r2",
            name = "Pancakes",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        )
    )
    private val generator = FakeRecipeImageGenerator()
    private val settings = FakeAiSettings(openAiApiKey = "sk-openai")
    private val useCase = GenerateRecipeImageUseCase(
        generator,
        repository,
        FakeImageStore(),
        settings
    )
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun worker(
        recipeId: String = "r1",
        prompt: String? = "A loaf of banana bread",
        runAttemptCount: Int = 0
    ): RecipeImageWorker =
        TestListenableWorkerBuilder<RecipeImageWorker>(context)
            // Literal keys: they are persisted by WorkManager, so they must not change
            .setInputData(workDataOf("recipe_id" to recipeId, "prompt" to prompt, "replace_image" to false))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = RecipeImageWorker(
                    appContext,
                    workerParameters,
                    useCase,
                    RecipeNotificationHelper(appContext),
                    RunningAccountWork(Provider { error("Not used by this test") })
                )
            })
            .build()

    @Test
    fun `input data uses the persisted keys and caps the prompt`() {
        val data = RecipeImageWorker.createInputData("r1", "x".repeat(5000), replaceImage = true)
        assertEquals("r1", data.getString("recipe_id"))
        assertEquals(2000, data.getString("prompt")?.length)
        assertTrue(data.getBoolean("replace_image", false))
    }

    @Test
    fun `generates the recipe's image`() = runTest {
        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(Triple("A loaf of banana bread", AiProviders.GPT_IMAGE_FLARE, "sk-openai")), generator.prompts)
        assertEquals("gs://bucket/stream.jpg", repository.recipes.value.getValue("r1").imageUrl)
    }

    @Test
    fun `transient failures retry, then notify and let the queue go on`() = runTest {
        generator.result = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 503))

        assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
        assertTrue(notifications.allNotifications.isEmpty())

        val last = worker(runAttemptCount = RecipeImageWorker.MAX_ATTEMPTS - 1).doWork()
        assertTrue(last is ListenableWorker.Result.Success)
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun `a key problem stops the queue, with one notification however many recipes fail`() = runTest {
        generator.result = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))

        assertTrue(worker("r1").doWork() is ListenableWorker.Result.Failure)
        assertTrue(worker("r2").doWork() is ListenableWorker.Result.Failure)

        assertEquals(1, notifications.allNotifications.size)
    }
}
