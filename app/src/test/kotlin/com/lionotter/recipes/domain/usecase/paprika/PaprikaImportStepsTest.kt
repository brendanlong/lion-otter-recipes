package com.lionotter.recipes.domain.usecase.paprika

import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.domain.port.PaprikaExportRecipe
import com.lionotter.recipes.domain.usecase.ParseHtmlUseCase
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakePageFetcher
import com.lionotter.recipes.testutil.FakePaprikaExportReader
import com.lionotter.recipes.testutil.FakeRecipeBatchParser
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.testutil.parsedRecipe
import com.lionotter.recipes.testutil.parsedWithUsage
import com.lionotter.recipes.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Instant
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

/**
 * The Paprika import state machine. Each [steps] call builds fresh instances over the same work
 * directory, like a worker run in a new process; the fakes for the AI server and the recipe
 * database outlive them.
 */
class PaprikaImportStepsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val batchParser = FakeRecipeBatchParser()
    private val repository = FakeRecipeRepository()
    private val settings = FakeAiSettings()
    private val debugLog = FakeImportDebugLog()
    private val recipeParser = FakeRecipeParser()
    private val imageStore = FakeImageStore().apply { streamUri = { size, ext -> "file:///images/photo-$size$ext" } }

    private var exportRecipes = (0..2).map { exportRecipe(it) }
    private var unreadableEntries = emptyList<String>()
    private var reader = FakePaprikaExportReader(exportRecipes)
    private val workDir: File by lazy { tempFolder.newFolder(IMPORT_ID) }

    private companion object {
        const val IMPORT_ID = "import-1"
        val RUNNING = Outcome.Success(BatchStatus(ended = false, succeeded = 0, processing = 3))
        val ENDED = Outcome.Success(BatchStatus(ended = true, succeeded = 3, processing = 0))
    }

    private fun exportRecipe(index: Int, photo: ByteArray? = null) = PaprikaExportRecipe(
        uid = "uid-$index",
        name = "Recipe $index",
        sourceUrl = null,
        imageUrl = null,
        photoData = photo?.let { Base64.getEncoder().encodeToString(it) },
        contentForAi = "content $index"
    )

    private fun steps(): PaprikaImportSteps {
        reader = FakePaprikaExportReader(exportRecipes, unreadableEntries)
        return PaprikaImportSteps(
            paprikaReader = reader,
            parseHtmlUseCase = ParseHtmlUseCase(
                recipeParser, FakePageContentExtractor(), repository, debugLog, settings, imageStore, FakeRecipeImageScheduler()
            ),
            recipeBatchParser = batchParser,
            aiSettings = settings,
            pageFetcher = FakePageFetcher(),
            imageStore = imageStore,
            recipeRepository = repository,
            json = AppJson,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    private fun store() = steps().store(workDir)

    private suspend fun run(
        selectedUids: Set<String>? = null,
        progress: MutableList<PaprikaImportProgress> = mutableListOf()
    ): PaprikaImportState {
        val steps = steps()
        return steps.run(steps.store(workDir), { ByteArrayInputStream(ByteArray(0)) }, selectedUids) { progress += it }
    }

    private suspend fun cancel() {
        val steps = steps()
        steps.cancel(steps.store(workDir))
    }

    private fun parsed(index: Int) = BatchItemOutcome.Parsed("recipe-$index", parsedWithUsage(parsedRecipe("Recipe $index")))

    private fun recipeId(customId: String) = PaprikaImportSteps.batchRecipeId(IMPORT_ID, customId)

    private suspend fun submit(): PaprikaImportState {
        batchParser.status = RUNNING
        return run()
    }

    private fun summary(state: PaprikaImportState) = (state as PaprikaImportState.Done).summary

    // --- Happy path and restarts ---

    @Test
    fun `the batch asks the AI to reuse the user's existing tags`() = runTest {
        repository.saveRecipe(
            Recipe(
                id = "existing",
                name = "Existing",
                tags = listOf("Dessert", "Baking"),
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0)
            )
        )
        submit()
        assertEquals(
            listOf(listOf("Baking", "Dessert")),
            batchParser.created.single().map { it.existingTags }.distinct()
        )
    }

    @Test
    fun `an import resumes from disk at every step and submits only once`() = runTest {
        assertEquals(PaprikaImportState.Submitted("batch-1"), submit())
        assertEquals(listOf("recipe-0", "recipe-1", "recipe-2"), batchParser.created.single().map { it.customId })

        assertTrue(run() is PaprikaImportState.Polling)
        assertTrue(run() is PaprikaImportState.Polling)
        assertEquals(2, batchParser.statusPolls)

        batchParser.status = ENDED
        batchParser.results = (0..2).map { parsed(it) }
        val done = run()

        assertEquals(PaprikaImportSummary(importedCount = 3, failedCount = 0), summary(done))
        assertEquals(1, batchParser.created.size)
        assertEquals((0..2).map { recipeId("recipe-$it") }.toSet(), repository.recipes.value.keys)
    }

    @Test
    fun `resume derives the state from the files`() = runTest {
        assertEquals(PaprikaImportState.Preparing, steps().resume(store()))
        submit()
        val polling = steps().resume(store())
        assertEquals("batch-1", (polling as PaprikaImportState.Polling).batch.batchId)
        store().appendProcessed("recipe-0", null)
        assertTrue(steps().resume(store()) is PaprikaImportState.SavingResults)
    }

    @Test
    fun `a batch still processing reports progress and is polled again`() = runTest {
        submit()
        batchParser.status = Outcome.Success(BatchStatus(ended = false, succeeded = 1, processing = 2))
        val progress = mutableListOf<PaprikaImportProgress>()
        assertTrue(run(progress = progress) is PaprikaImportState.Polling)
        assertEquals(listOf(PaprikaImportProgress.WaitingForBatch(3, 1, 2)), progress)
    }

    @Test
    fun `a worker stopped while the batch is created still saves it, and cancelling cancels it once`() = runTest {
        batchParser.status = RUNNING
        val job = launch { run() }
        batchParser.onCreate = { job.cancel() }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals("batch-1", store().readBatch()?.batchId)

        cancel()
        cancel()
        assertEquals(listOf("batch-1"), batchParser.cancelled)
        assertEquals(PaprikaImportState.Failed(RecipeError.Interrupted, allowRetry = false), run())
        assertEquals(1, batchParser.created.size)
    }

    @Test
    fun `a run stopped while saving results finishes later without saving anything twice`() = runTest {
        submit()
        batchParser.status = ENDED
        batchParser.results = (0..2).map { parsed(it) }
        lateinit var job: Job
        job = launch {
            val steps = steps()
            steps.run(steps.store(workDir), { error("not read") }, null) { progress ->
                if (progress is PaprikaImportProgress.ImportingRecipe) job.cancel()
            }
        }
        job.join()
        val savedBeforeRestart = store().readProcessed().keys
        assertTrue(savedBeforeRestart.size in 1..2)

        val done = run()

        assertEquals(3, summary(done).importedCount)
        assertEquals(3, store().readProcessed().size)
        assertEquals((0..2).map { recipeId("recipe-$it") }.toSet(), repository.recipes.value.keys)
        assertEquals(3, repository.saved.size)
    }

    @Test
    fun `a result saved but not logged is overwritten rather than duplicated`() = runTest {
        submit()
        batchParser.status = ENDED
        batchParser.results = (0..2).map { parsed(it) }
        run()
        // Lose the last log line, as if the process died right after saving the recipe
        val log = File(workDir, "processed.log")
        log.writeText(log.readLines().take(2).joinToString("") { "$it\n" })

        val done = run()

        assertEquals(3, summary(done).importedCount)
        assertEquals(4, repository.saved.size)
        assertEquals(3, repository.recipes.value.size)
    }

    @Test
    fun `rerunning a finished import saves nothing again`() = runTest {
        submit()
        batchParser.status = ENDED
        batchParser.results = listOf(parsed(0), parsed(1), parsed(2))
        val first = run()
        val second = run()
        assertEquals(summary(first), summary(second))
        assertEquals(3, repository.saved.size)
    }

    // --- Cancel in each state ---

    @Test
    fun `cancelling before anything was submitted cancels nothing`() = runTest {
        cancel()
        assertTrue(batchParser.cancelled.isEmpty())
    }

    @Test
    fun `cancelling while polling cancels the batch exactly once and stops the import`() = runTest {
        submit()
        run()
        cancel()
        cancel()
        assertEquals(listOf("batch-1"), batchParser.cancelled)
        assertEquals(PaprikaImportState.Failed(RecipeError.Interrupted, allowRetry = false), run())
        assertEquals(1, batchParser.statusPolls)
    }

    @Test
    fun `a failed cancel is tried again by the next caller`() = runTest {
        submit()
        batchParser.cancelResult = Outcome.Failure(RecipeError.Ai(AiFailure.NETWORK))
        cancel()
        batchParser.cancelResult = Outcome.Success(Unit)
        cancel()
        cancel()
        assertEquals(listOf("batch-1", "batch-1"), batchParser.cancelled)
    }

    @Test
    fun `cancelling while saving results doesn't cancel the ended batch`() = runTest {
        submit()
        store().appendProcessed("recipe-0", null)
        cancel()
        assertTrue(batchParser.cancelled.isEmpty())
    }

    // --- Partial results, errors ---

    @Test
    fun `partial results save what succeeded and report what failed`() = runTest {
        settings.debuggingFlow.value = true
        submit()
        batchParser.status = ENDED
        batchParser.results = listOf(
            parsed(0),
            BatchItemOutcome.Failed("recipe-1", "Not a recipe: it's a poem")
        )

        val summary = summary(run())

        assertEquals(1, summary.importedCount)
        assertEquals(2, summary.failedCount)
        assertEquals(setOf("Recipe 1", "Recipe 2"), summary.failedRecipes.toSet())
        assertEquals("Recipe 0", repository.saved.single().name)
        assertEquals(recipeId("recipe-0"), repository.saved.single().id)
        assertTrue(debugLog.entries.all { it.batchMode })
        assertEquals(
            setOf("Not a recipe: it's a poem", "No AI result was returned for this recipe"),
            debugLog.entries.filter { it.isError }.map { it.errorMessage }.toSet()
        )
    }

    @Test
    fun `a permanent poll error ends the import without retries`() = runTest {
        submit()
        batchParser.status = Outcome.Failure(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401))
        assertEquals(
            PaprikaImportState.Failed(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401), allowRetry = false),
            run()
        )
    }

    @Test
    fun `transient poll failures are counted across runs until the import gives up`() = runTest {
        submit()
        val overloaded = RecipeError.Ai(AiFailure.OVERLOADED, 529)
        batchParser.status = Outcome.Failure(overloaded)
        repeat(30) { assertTrue(run() is PaprikaImportState.Polling) }
        assertEquals(PaprikaImportState.Failed(overloaded, allowRetry = false), run())
    }

    @Test
    fun `a successful poll resets the failure count`() = runTest {
        submit()
        batchParser.status = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529))
        repeat(30) { run() }
        batchParser.status = RUNNING
        run()
        batchParser.status = Outcome.Failure(RecipeError.Ai(AiFailure.OVERLOADED, 529))
        assertTrue(run() is PaprikaImportState.Polling)
    }

    @Test
    fun `a transient download failure polls again and saves nothing`() = runTest {
        submit()
        batchParser.status = ENDED
        batchParser.resultsError = Outcome.Failure(RecipeError.Ai(AiFailure.NETWORK))
        assertTrue(run() is PaprikaImportState.Polling)
        assertTrue(repository.saved.isEmpty())

        batchParser.resultsError = null
        batchParser.results = (0..2).map { parsed(it) }
        assertEquals(3, summary(run()).importedCount)
    }

    @Test
    fun `no API key is reported as such before reading the export or touching the batch`() = runTest {
        settings.apiKeyFlow.value = null
        assertEquals(PaprikaImportState.Failed(RecipeError.NoApiKey), run())
        assertEquals(0, reader.reads)
        assertTrue(batchParser.created.isEmpty())

        settings.apiKeyFlow.value = "sk-ant-test"
        submit()
        settings.apiKeyFlow.value = ""
        assertEquals(PaprikaImportState.Failed(RecipeError.NoApiKey), run())
        assertEquals(0, batchParser.statusPolls)
    }

    @Test
    fun `a batch is followed with its own provider's key after the import model changes`() = runTest {
        settings.openAiApiKeyFlow.value = "sk-proj-test"
        submit()
        assertEquals(AiProviders.CLAUDE_SONNET, batchParser.createdModel)

        settings.modelFlow.value = AiProviders.GPT_SOL
        run()
        assertEquals(AiProviders.CLAUDE_SONNET to "sk-ant-test", batchParser.calls.last())

        settings.apiKeyFlow.value = null
        assertEquals(PaprikaImportState.Failed(RecipeError.NoApiKey), run())
    }

    @Test
    fun `a failed submission fails the import and leaves nothing to resume`() = runTest {
        batchParser.createResult = Outcome.Failure(RecipeError.Ai(AiFailure.RATE_LIMITED, 429))
        assertEquals(PaprikaImportState.Failed(RecipeError.Ai(AiFailure.RATE_LIMITED, 429)), run())
        assertEquals(PaprikaImportState.Preparing, steps().resume(store()))
    }

    // --- Recipe limit, selection, photos, single recipe ---

    @Test
    fun `the recipe limit is checked before submitting`() = runTest {
        repository.countOverride = ResourceLimits.MAX_RECIPES
        assertEquals(
            PaprikaImportState.Failed(RecipeError.LimitReached(ResourceLimits.MAX_RECIPES, ResourceLimits.MAX_RECIPES)),
            run()
        )
        assertTrue(batchParser.created.isEmpty())
    }

    @Test
    fun `recipes over the limit aren't submitted and are reported as failed`() = runTest {
        repository.countOverride = ResourceLimits.MAX_RECIPES - 2
        submit()
        assertEquals(listOf("content 0", "content 1"), batchParser.created.single().map { it.content })

        batchParser.status = ENDED
        batchParser.results = listOf(parsed(0), parsed(1))
        val summary = summary(run())
        assertEquals(2, summary.importedCount)
        assertEquals(listOf("Recipe 2"), summary.failedRecipes)
    }

    @Test
    fun `unselected recipes are skipped and unreadable entries only count when importing everything`() = runTest {
        unreadableEntries = listOf("export/Broken.paprikarecipe")
        batchParser.status = RUNNING
        run(selectedUids = setOf("uid-0", "uid-2"))
        val requests = batchParser.created.single()
        assertEquals(listOf("recipe-0", "recipe-1"), requests.map { it.customId })
        assertEquals(listOf("content 0", "content 2"), requests.map { it.content })
        assertEquals(emptyList<String>(), store().readBatch()?.preFailedNames)
    }

    @Test
    fun `unreadable entries are reported as failed when importing everything`() = runTest {
        unreadableEntries = listOf("export/Broken.paprikarecipe")
        submit()
        batchParser.status = ENDED
        batchParser.results = (0..2).map { parsed(it) }
        assertEquals(listOf("Broken"), summary(run()).failedRecipes)
    }

    @Test
    fun `an export with only unreadable entries fails without submitting`() = runTest {
        exportRecipes = emptyList()
        unreadableEntries = listOf("Broken.paprikarecipe")
        assertEquals(
            PaprikaImportState.Failed(
                RecipeError.Archive(ArchiveFailure.NO_READABLE_RECIPES)
            ),
            run()
        )
        assertTrue(batchParser.created.isEmpty())
    }

    @Test
    fun `photos are attached to the recipes they came with`() = runTest {
        exportRecipes = listOf(exportRecipe(0), exportRecipe(1, ByteArray(5)), exportRecipe(2, ByteArray(7)))
        submit()
        batchParser.status = ENDED
        batchParser.results = (0..2).map { parsed(it) }
        run()

        val recipes = repository.recipes.value
        assertNull(recipes.getValue(recipeId("recipe-0")).imageUrl)
        assertEquals("gs://bucket/photo-5.jpg", recipes.getValue(recipeId("recipe-1")).imageUrl)
        assertEquals("gs://bucket/photo-7.jpg", recipes.getValue(recipeId("recipe-2")).imageUrl)
    }

    @Test
    fun `a single recipe is parsed directly without a batch`() = runTest {
        exportRecipes = listOf(exportRecipe(0))
        val done = run()
        assertEquals(PaprikaImportSummary(importedCount = 1, failedCount = 0), summary(done))
        assertTrue(batchParser.created.isEmpty())
        assertEquals("content 0", recipeParser.requests.single().content)
    }

    // --- Malformed state ---

    @Test
    fun `unreadable batch state fails instead of submitting again`() = runTest {
        File(workDir, "batch.json").writeText("{not json")
        assertEquals(PaprikaImportState.Failed(RecipeError.Unexpected, allowRetry = false), run())
        cancel()
        assertTrue(batchParser.created.isEmpty())
        assertTrue(batchParser.cancelled.isEmpty())
    }

    @Test
    fun `a log line cut short by a crash is ignored`() = runTest {
        submit()
        batchParser.status = ENDED
        batchParser.results = (0..2).map { parsed(it) }
        File(workDir, "processed.log").writeText("recipe-0\nrecipe-")

        val done = run()

        assertEquals(3, summary(done).importedCount)
        assertEquals(setOf("recipe-0", "recipe-1", "recipe-2"), store().readProcessed().keys)
        assertEquals(setOf(recipeId("recipe-1"), recipeId("recipe-2")), repository.saved.map { it.id }.toSet())
    }

    @Test
    fun `results for unknown custom IDs are ignored`() = runTest {
        submit()
        batchParser.status = ENDED
        batchParser.results = (0..2).map { parsed(it) } +
            BatchItemOutcome.Parsed("recipe-9", parsedWithUsage(parsedRecipe("Stray")))
        assertEquals(3, summary(run()).importedCount)
        assertEquals(3, repository.saved.size)
    }

    @Test
    fun `batch state written by the previous app version still loads`() = runTest {
        File(workDir, "batch.json").writeText(
            """{"batchId":"msgbatch_old","model":"claude-sonnet-5-5","thinkingEnabled":false,""" +
                """"submittedAtMillis":1,"recipes":[{"customId":"recipe-0","uid":"u0","name":"Old",""" +
                """"sourceUrl":null,"imageUrl":null,"photoFile":null,"contentForAi":"c0"}],"preFailedNames":["Lost"]}"""
        )
        File(workDir, "poll_failures").writeText("3")
        batchParser.status = ENDED
        batchParser.results = listOf(BatchItemOutcome.Parsed("recipe-0", parsedWithUsage(parsedRecipe("Old"))))

        val summary = summary(run())

        assertEquals(PaprikaImportSummary(importedCount = 1, failedCount = 1, failedRecipes = listOf("Lost")), summary)
        assertTrue(batchParser.created.isEmpty())
    }

    // --- Recipe IDs ---

    @Test
    fun `batch recipe id is stable for the same import and custom id`() {
        assertEquals(
            PaprikaImportSteps.batchRecipeId("import-1", "recipe-0"),
            PaprikaImportSteps.batchRecipeId("import-1", "recipe-0")
        )
    }

    @Test
    fun `batch recipe id differs across recipes and imports`() {
        val id = PaprikaImportSteps.batchRecipeId("import-1", "recipe-0")
        assertNotEquals(id, PaprikaImportSteps.batchRecipeId("import-1", "recipe-1"))
        assertNotEquals(id, PaprikaImportSteps.batchRecipeId("import-2", "recipe-0"))
    }
}
