package com.lionotter.recipes.domain.usecase

import android.util.Log
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakePageFetcher
import com.lionotter.recipes.testutil.FakeRecipeBatchParser
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.parsedRecipe
import com.lionotter.recipes.testutil.parsedWithUsage
import com.lionotter.recipes.util.AppJson
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportPaprikaUseCaseTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val batchParser = FakeRecipeBatchParser()
    private val repository = FakeRecipeRepository()
    private val settings = FakeAiSettings()
    private val debugLog = FakeImportDebugLog()

    private val useCase = ImportPaprikaUseCase(
        paprikaParser = PaprikaParser(AppJson),
        parseHtmlUseCase = ParseHtmlUseCase(
            FakeRecipeParser(), FakePageContentExtractor(), repository, debugLog, settings, FakeImageStore()
        ),
        recipeBatchParser = batchParser,
        aiSettings = settings,
        pageFetcher = FakePageFetcher(),
        imageStore = FakeImageStore(),
        recipeRepository = repository,
        json = AppJson
    )

    private val recipes = listOf("Scones", "Not food", "Lost").mapIndexed { index, name ->
        ImportPaprikaUseCase.PreparedRecipe(
            customId = "recipe-$index",
            uid = "uid-$index",
            name = name,
            contentForAi = "content $index"
        )
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun submittedImport(): File = tempFolder.newFolder("import-1").also { dir ->
        val state = ImportPaprikaUseCase.BatchState(
            batchId = "batch-1",
            model = AiModels.MODEL_SONNET,
            thinkingEnabled = false,
            submittedAtMillis = 0,
            recipes = recipes,
            preFailedNames = listOf("Unreadable")
        )
        File(dir, "batch.json").writeText(AppJson.encodeToString(state))
    }

    private suspend fun runStep(workDir: File, progress: MutableList<ImportPaprikaUseCase.ImportProgress> = mutableListOf()) =
        useCase.runStep(workDir, { throw AssertionError("The export is only read before submitting") }, null) {
            progress += it
        }

    @Test
    fun `batch recipe id is stable for the same import and custom id`() {
        assertEquals(
            ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-0"),
            ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-0")
        )
    }

    @Test
    fun `batch recipe id differs across recipes and imports`() {
        val id = ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-0")
        assertNotEquals(id, ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-1"))
        assertNotEquals(id, ImportPaprikaUseCase.batchRecipeId("import-2", "recipe-0"))
    }

    @Test
    fun `a batch still processing is polled again`() = runTest {
        batchParser.status = Outcome.Success(BatchStatus(ended = false, succeeded = 1, processing = 2))
        val progress = mutableListOf<ImportPaprikaUseCase.ImportProgress>()
        assertEquals(ImportPaprikaUseCase.StepResult.PollAgain, runStep(submittedImport(), progress))
        assertEquals(listOf(ImportPaprikaUseCase.ImportProgress.WaitingForBatch(3, 1, 2)), progress)
    }

    @Test
    fun `an ended batch saves its results and reports what failed`() = runTest {
        settings.debuggingFlow.value = true
        batchParser.results = listOf(
            BatchItemOutcome.Parsed("recipe-0", parsedWithUsage(parsedRecipe("Scones"))),
            BatchItemOutcome.Failed("recipe-1", "Not a recipe: it's a poem")
        )
        val workDir = submittedImport()

        val result = runStep(workDir)

        val summary = ((result as ImportPaprikaUseCase.StepResult.Finished).result as Outcome.Success).value
        assertEquals(1, summary.importedCount)
        assertEquals(3, summary.failedCount)
        assertEquals(setOf("Not food", "Lost", "Unreadable"), summary.failedRecipes.toSet())
        val saved = repository.saved.single()
        assertEquals(ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-0"), saved.id)
        assertEquals("Scones", saved.name)
        assertTrue(debugLog.entries.all { it.batchMode })
        assertEquals(
            setOf("Not a recipe: it's a poem", "No AI result was returned for this recipe"),
            debugLog.entries.filter { it.isError }.map { it.errorMessage }.toSet()
        )

        // A rerun after everything was processed doesn't save again
        runStep(workDir)
        assertEquals(1, repository.saved.size)
    }

    @Test
    fun `a poll failure that won't go away ends the import`() = runTest {
        batchParser.status = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))
        assertEquals(
            ImportPaprikaUseCase.StepResult.Finished(
                Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401)),
                allowRetry = false
            ),
            runStep(submittedImport())
        )
    }

    @Test
    fun `transient poll and download failures poll again`() = runTest {
        val workDir = submittedImport()
        batchParser.status = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529))
        assertEquals(ImportPaprikaUseCase.StepResult.PollAgain, runStep(workDir))

        batchParser.status = Outcome.Success(BatchStatus(ended = true, succeeded = 3, processing = 0))
        batchParser.resultsError = Outcome.Failure(RecipeError.Ai(AiFailure.NETWORK))
        assertEquals(ImportPaprikaUseCase.StepResult.PollAgain, runStep(workDir))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `no API key fails before touching the batch`() = runTest {
        settings.apiKeyFlow.value = null
        assertEquals(
            ImportPaprikaUseCase.StepResult.Finished(Outcome.Failure(RecipeError.NoApiKey)),
            runStep(submittedImport())
        )
    }

    @Test
    fun `cancelling cancels the submitted batch`() = runTest {
        useCase.cancelBatch(submittedImport())
        assertEquals(listOf("batch-1"), batchParser.cancelled)
    }
}
