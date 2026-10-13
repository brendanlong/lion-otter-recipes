package com.lionotter.recipes.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.ImageChange
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.EditRecipeUseCase
import com.lionotter.recipes.domain.usecase.ParseHtmlUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.inject.Provider
import kotlin.time.Instant

/** The copy image handed to "save as copy" work is stored for the copy or released, never leaked. */
@RunWith(RobolectricTestRunner::class)
class RecipeEditWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = FakeRecipeRepository(
        Recipe(
            id = "r1",
            name = "Pasta",
            imageUrl = "gs://bucket/old.jpg",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        )
    )
    private val parser = FakeRecipeParser()
    private val imageStore = FakeImageStore()

    @Suppress("InjectDispatcher") // The ImportInputStore only writes the test's input file
    private val inputStore = ImportInputStore(context, Dispatchers.Unconfined)

    private val useCase = EditRecipeUseCase(
        ParseHtmlUseCase(parser, FakePageContentExtractor(), repository, FakeImportDebugLog(), FakeAiSettings(), imageStore, FakeRecipeImageScheduler()),
        repository,
        imageStore
    )

    private val copyImage = ImageChange.Replace("file:///images/picked-copy.jpg")

    private fun worker(runAttemptCount: Int = 0): RecipeEditWorker =
        TestListenableWorkerBuilder<RecipeEditWorker>(context)
            .setInputData(
                RecipeEditWorker.createInputData(
                    recipeId = "r1",
                    markdownPath = inputStore.writeWorkText("# Pasta"),
                    model = null,
                    thinkingEnabled = null,
                    saveAsCopy = true,
                    copyRecipeId = "copy1",
                    copyImage = copyImage
                )
            )
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = RecipeEditWorker(
                    appContext,
                    workerParameters,
                    useCase,
                    inputStore,
                    imageStore,
                    RecipeNotificationHelper(appContext),
                    RunningAccountWork(Provider { error("Not used by this test") })
                )
            })
            .build()

    @Test
    fun `the image is stored for the copy`() = runTest {
        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("gs://bucket/picked-copy.jpg", repository.recipes.value.getValue("copy1").imageUrl)
        assertEquals(emptyList<String>(), imageStore.deletedLocal)
    }

    @Test
    fun `the image is deleted when the copy fails for good`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(listOf(copyImage.localUri), imageStore.deletedLocal)
    }

    @Test
    fun `the image is kept for a retry`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529))

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(emptyList<String>(), imageStore.deletedLocal)
    }

    @Test
    fun `the image is deleted when the last attempt fails`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529))

        val result = worker(runAttemptCount = BaseRecipeWorker.MAX_ATTEMPTS - 1).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(listOf(copyImage.localUri), imageStore.deletedLocal)
    }

    @Test
    fun `the image is deleted when the edit input is gone`() = runTest {
        val worker = worker()
        java.io.File(worker.inputData.getString("markdown_path")!!).delete()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(listOf(copyImage.localUri), imageStore.deletedLocal)
    }
}
