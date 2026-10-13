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
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.FetchedPage
import com.lionotter.recipes.domain.usecase.ImportRecipeUseCase
import com.lionotter.recipes.domain.usecase.ParseHtmlUseCase
import com.lionotter.recipes.domain.usecase.RegenerateRecipeUseCase
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakePageFetcher
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.time.Instant

/**
 * A page captured in the in-app browser reaches the import and regenerate workers through
 * [ImportInputStore] files (too large for WorkManager Data), and the URL-only input of earlier
 * versions still downloads the page.
 */
@RunWith(RobolectricTestRunner::class)
class CapturedPageWorkTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Suppress("InjectDispatcher") // The ImportInputStore only writes the test's input files
    private val inputStore = ImportInputStore(context, Dispatchers.Unconfined)

    private val url = "https://example.com/bread"
    private val parser = FakeRecipeParser()
    private val imageStore = FakeImageStore()
    private val repository = FakeRecipeRepository(
        Recipe(
            id = "r1",
            name = "Old Bread",
            sourceUrl = url,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        )
    )
    private val fetcher = FakePageFetcher(mapOf(url to Outcome.Success(FetchedPage("<html>downloaded</html>"))))
    private val parseHtml =
        ParseHtmlUseCase(parser, FakePageContentExtractor(), repository, FakeImportDebugLog(), FakeAiSettings(), imageStore)

    private val factory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker = when (workerClassName) {
            RecipeImportWorker::class.java.name -> RecipeImportWorker(
                appContext,
                workerParameters,
                ImportRecipeUseCase(fetcher, FakePageContentExtractor(), parseHtml),
                inputStore,
                RecipeNotificationHelper(appContext)
            )
            else -> RecipeRegenerateWorker(
                appContext,
                workerParameters,
                RegenerateRecipeUseCase(parseHtml, repository, fetcher, FakePageContentExtractor(), imageStore),
                inputStore,
                RecipeNotificationHelper(appContext)
            )
        }
    }

    private fun importWorker(importId: String) = TestListenableWorkerBuilder<RecipeImportWorker>(context)
        .setInputData(RecipeImportWorker.createInputData(url, importId))
        .setWorkerFactory(factory)
        .build()

    private fun regenerateWorker(pageHtmlPath: String?, runAttemptCount: Int = 0) =
        TestListenableWorkerBuilder<RecipeRegenerateWorker>(context)
            .setInputData(RecipeRegenerateWorker.createInputData("r1", model = null, thinkingEnabled = null, pageHtmlPath))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(factory)
            .build()

    @Test
    fun `the captured page moves into the import and is read back`() {
        val path = inputStore.writeWorkText("<html>rendered</html>")

        inputStore.adoptPageHtml("import-1", path)

        assertFalse(File(path).exists())
        assertEquals("<html>rendered</html>", inputStore.readPageHtml("import-1"))
    }

    @Test
    fun `a URL-only import has no page and reading it creates no files`() {
        assertNull(inputStore.readPageHtml("import-2"))
        assertFalse(inputStore.hasImport("import-2"))
    }

    @Test(expected = IOException::class)
    fun `adopting a missing page fails`() {
        inputStore.adoptPageHtml("import-3", File(context.filesDir, "work-inputs/missing.txt").path)
    }

    @Test(expected = IOException::class)
    fun `only work inputs can be adopted`() {
        val outside = File(context.filesDir, "elsewhere.txt").apply { writeText("x") }
        inputStore.adoptPageHtml("import-4", outside.path)
    }

    @Test
    fun `an import with a captured page doesn't download it and cleans up`() = runTest {
        inputStore.adoptPageHtml("import-5", inputStore.writeWorkText("<html>rendered</html>"))

        val result = importWorker("import-5").doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(fetcher.fetched.isEmpty())
        assertEquals("extracted(<html>rendered</html>)", parser.requests.single().content)
        assertFalse(inputStore.hasImport("import-5"))
    }

    @Test
    fun `a failed import keeps the captured page for a retry`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))
        inputStore.adoptPageHtml("import-6", inputStore.writeWorkText("<html>rendered</html>"))

        val result = importWorker("import-6").doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("<html>rendered</html>", inputStore.readPageHtml("import-6"))
    }

    @Test
    fun `an import without a captured page downloads it`() = runTest {
        val result = importWorker("import-7").doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(url), fetcher.fetched)
        assertEquals("extracted(<html>downloaded</html>)", parser.requests.single().content)
    }

    @Test
    fun `a regeneration uses the captured page and deletes it`() = runTest {
        val path = inputStore.writeWorkText("<html>rendered</html>")

        val result = regenerateWorker(path).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(fetcher.fetched.isEmpty())
        assertEquals("extracted(<html>rendered</html>)", parser.requests.single().content)
        assertFalse(File(path).exists())
    }

    @Test
    fun `a regeneration that will be retried keeps the captured page`() = runTest {
        parser.result = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529))
        val path = inputStore.writeWorkText("<html>rendered</html>")

        val result = regenerateWorker(path).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue(File(path).exists())
    }

    @Test
    fun `a regeneration whose captured page is gone fails instead of downloading`() = runTest {
        val path = inputStore.writeWorkText("<html>rendered</html>")
        File(path).delete()

        val result = regenerateWorker(path).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(fetcher.fetched.isEmpty())
    }

    @Test
    fun `a regeneration without a captured page downloads it`() = runTest {
        val result = regenerateWorker(pageHtmlPath = null).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(url), fetcher.fetched)
    }
}
