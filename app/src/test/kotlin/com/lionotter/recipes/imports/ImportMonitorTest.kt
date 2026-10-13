package com.lionotter.recipes.imports

import android.util.Log
import androidx.work.WorkInfo
import app.cash.turbine.test
import com.lionotter.recipes.data.local.PendingImportStatus
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.worker.PaprikaImportWork
import com.lionotter.recipes.worker.PaprikaImportWorker
import com.lionotter.recipes.worker.RecipeImportWork
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.ZipImportWork
import com.lionotter.recipes.worker.ZipImportWorker
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ImportMonitorTest {

    private val dao = FakePendingImportDao()
    private val work = FakeImportWork()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun TestScope.createMonitor() = ImportMonitor(work.workManager, PendingImportRepository(dao), testAppScope())

    /** Adds a URL import row with work in [state] and returns the work ID. */
    private fun addImport(
        id: String,
        state: WorkInfo.State,
        tag: String = com.lionotter.recipes.worker.RecipeImportWorker.TAG_RECIPE_IMPORT,
        status: PendingImportStatus = PendingImportStatus.PENDING,
        name: String? = null
    ): UUID {
        val workId = UUID.randomUUID()
        dao.rows.value = dao.rows.value +
            pendingImport(id, workId.toString(), name = name, status = status, createdAt = dao.rows.value.size.toLong())
        work.assignUniqueName(RecipeWorkScheduler.importWorkName(id), workId)
        work.put(workInfo(workId, state, tag))
        return workId
    }

    @Test
    fun `initial state is empty`() = runTest {
        val monitor = createMonitor()
        monitor.inProgressRecipes.test {
            assertEquals(emptyMap<String, InProgressRecipe>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `queued import is listed under its URL until a name is known`() = runTest {
        val monitor = createMonitor()
        addImport("import-1", WorkInfo.State.ENQUEUED)
        advanceUntilIdle()

        val recipe = monitor.inProgressRecipes.value["import-1"]!!
        assertEquals("https://example.com/import-1", recipe.name)
        assertEquals(ImportStage.QUEUED, recipe.stage)
        assertTrue(!recipe.isFailed)
    }

    @Test
    fun `live stage comes from WorkInfo without writing every tick to the database`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.RUNNING)
        advanceUntilIdle()
        val before = dao["import-1"]

        work.put(
            workInfo(workId, WorkInfo.State.RUNNING, progress = RecipeImportWork.Progress.FetchingPage.toData())
        )
        advanceUntilIdle()
        assertEquals(ImportStage.FETCHING_PAGE, monitor.inProgressRecipes.value["import-1"]!!.stage)

        work.put(workInfo(workId, WorkInfo.State.RUNNING, progress = RecipeImportWork.Progress.Saving.toData()))
        advanceUntilIdle()
        assertEquals(ImportStage.SAVING, monitor.inProgressRecipes.value["import-1"]!!.stage)
        assertEquals(before, dao["import-1"])
    }

    @Test
    fun `page title and image are persisted once known`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.RUNNING)
        work.put(
            workInfo(
                workId,
                WorkInfo.State.RUNNING,
                progress = RecipeImportWork.Progress.MetadataAvailable("Pasta", "https://img").toData()
            )
        )
        advanceUntilIdle()

        assertEquals("Pasta", dao["import-1"]!!.name)
        assertEquals("https://img", dao["import-1"]!!.imageUrl)
        assertEquals(PendingImportStatus.PENDING, dao["import-1"]!!.status)
        val recipe = monitor.inProgressRecipes.value["import-1"]!!
        assertEquals("Pasta", recipe.name)
        assertEquals(ImportStage.ANALYZING, recipe.stage)
    }

    @Test
    fun `zip progress replaces the name while running`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("zip-1", WorkInfo.State.RUNNING, tag = ZipImportWorker.TAG_ZIP_IMPORT, name = "Importing 3 recipes")
        work.put(
            workInfo(
                workId,
                WorkInfo.State.RUNNING,
                tag = ZipImportWorker.TAG_ZIP_IMPORT,
                progress = ZipImportWork.Progress.ImportingRecipe("Soup", 2, 3).toData()
            )
        )
        advanceUntilIdle()

        assertEquals("Importing 2/3: Soup", monitor.inProgressRecipes.value["zip-1"]!!.name)
        assertEquals("Importing 3 recipes", dao["zip-1"]!!.name)
    }

    @Test
    fun `paprika waiting state is persisted so it shows between polls`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("pap-1", WorkInfo.State.RUNNING, tag = PaprikaImportWorker.TAG_PAPRIKA_IMPORT)
        work.put(
            workInfo(
                workId,
                WorkInfo.State.RUNNING,
                tag = PaprikaImportWorker.TAG_PAPRIKA_IMPORT,
                progress = PaprikaImportWork.Progress.WaitingForBatch(total = 10, succeeded = 4).toData()
            )
        )
        advanceUntilIdle()
        assertEquals(PendingImportStatus.WAITING_FOR_BATCH, dao["pap-1"]!!.status)

        // The step finishes and the next poll is queued under a new request ID
        val next = UUID.randomUUID()
        PendingImportRepository(dao).updateWorkManagerId("pap-1", next.toString())
        work.put(workInfo(next, WorkInfo.State.ENQUEUED, tag = PaprikaImportWorker.TAG_PAPRIKA_IMPORT))
        work.put(
            workInfo(
                workId,
                WorkInfo.State.SUCCEEDED,
                tag = PaprikaImportWorker.TAG_PAPRIKA_IMPORT,
                output = PaprikaImportWork.Outcome.Continued("pap-1").toData()
            )
        )
        advanceUntilIdle()

        val recipe = monitor.inProgressRecipes.value["pap-1"]!!
        assertEquals(ImportStage.WAITING_FOR_BATCH, recipe.stage)
        assertEquals("Importing 10 recipes from Paprika (4 done by AI)", recipe.name)
    }

    @Test
    fun `a continued paprika step never removes the import`() = runTest {
        createMonitor()
        val workId = addImport("pap-1", WorkInfo.State.RUNNING, tag = PaprikaImportWorker.TAG_PAPRIKA_IMPORT)
        work.put(
            workInfo(
                workId,
                WorkInfo.State.SUCCEEDED,
                tag = PaprikaImportWorker.TAG_PAPRIKA_IMPORT,
                output = PaprikaImportWork.Outcome.Continued("pap-1").toData()
            )
        )
        advanceUntilIdle()

        assertEquals(workId.toString(), dao["pap-1"]?.workManagerId)
    }

    @Test
    fun `importState during a retry follows the new request, not the old failure`() = runTest {
        val monitor = createMonitor()
        val oldWork = addImport("import-1", WorkInfo.State.FAILED, status = PendingImportStatus.FAILED)
        advanceUntilIdle()

        val repository = PendingImportRepository(dao)
        repository.updateWorkManagerId("import-1", UUID.randomUUID().toString())
        repository.updateStatusWithError("import-1", PendingImportStatus.PENDING, null)

        assertEquals(ImportState.Running(ImportStage.QUEUED), monitor.importState("import-1").first())
        assertEquals(WorkInfo.State.FAILED, work[oldWork]?.state)
    }

    @Test
    fun `failed work marks the matching import failed with its error`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.RUNNING)
        val error = RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403)
        work.put(
            workInfo(
                workId,
                WorkInfo.State.FAILED,
                output = RecipeImportWork.Outcome.Failed(error, "import-1").toData()
            )
        )
        advanceUntilIdle()

        assertEquals(PendingImportStatus.FAILED, dao["import-1"]!!.status)
        assertEquals(error, dao["import-1"]!!.error)
        assertEquals(error, monitor.inProgressRecipes.value["import-1"]!!.error)
    }

    @Test
    fun `a failure stored by an older version is still shown`() = runTest {
        val monitor = createMonitor()
        dao.insertPendingImport(
            pendingImport(
                "import-1",
                workManagerId = null,
                status = PendingImportStatus.FAILED,
                error = RecipeError.Legacy("The site blocked the request (403).")
            )
        )
        advanceUntilIdle()

        assertEquals(
            RecipeError.Legacy("The site blocked the request (403)."),
            monitor.inProgressRecipes.value["import-1"]!!.error
        )
    }

    @Test
    fun `failed work without output still marks the import failed`() = runTest {
        createMonitor()
        val workId = addImport("import-1", WorkInfo.State.RUNNING)
        work.put(workInfo(workId, WorkInfo.State.FAILED))
        advanceUntilIdle()

        assertEquals(PendingImportStatus.FAILED, dao["import-1"]!!.status)
        assertEquals(RecipeError.Unexpected, dao["import-1"]!!.error)
    }

    @Test
    fun `failed import stays listed after its work is pruned`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.FAILED)
        advanceUntilIdle()
        work.remove(workId)
        advanceUntilIdle()

        assertTrue(monitor.inProgressRecipes.value["import-1"]!!.isFailed)
    }

    @Test
    fun `succeeded work removes the import and unrelated work is ignored`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.RUNNING)
        addImport("import-2", WorkInfo.State.RUNNING)
        work.put(workInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED))
        work.put(workInfo(workId, WorkInfo.State.SUCCEEDED))
        advanceUntilIdle()

        assertEquals(listOf("import-2"), dao.rows.value.map { it.id })
        assertEquals(setOf("import-2"), monitor.inProgressRecipes.value.keys)
    }

    @Test
    fun `importState reports the new recipe after the row is removed`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.ENQUEUED)

        monitor.importState("import-1").test {
            assertEquals(ImportState.Running(ImportStage.QUEUED), awaitItem())
            work.put(
                workInfo(
                    workId,
                    WorkInfo.State.RUNNING,
                    progress = RecipeImportWork.Progress.Parsing("Pasta").toData()
                )
            )
            assertEquals(ImportState.Running(ImportStage.ANALYZING), awaitItem())
            work.put(
                workInfo(
                    workId,
                    WorkInfo.State.SUCCEEDED,
                    output = RecipeImportWork.Outcome.Success("import-1", "recipe-9", "Pasta").toData()
                )
            )
            assertEquals(ImportState.Succeeded("recipe-9"), awaitItem())
            advanceUntilIdle()
            assertNull(dao["import-1"])
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importState of success without a recipe ID still finishes`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.ENQUEUED)
        work.put(workInfo(workId, WorkInfo.State.SUCCEEDED))

        assertEquals(ImportState.Succeeded(null), monitor.importState("import-1").first())
    }

    @Test
    fun `importState reports a missing API key`() = runTest {
        val monitor = createMonitor()
        val workId = addImport("import-1", WorkInfo.State.ENQUEUED)
        work.put(
            workInfo(
                workId,
                WorkInfo.State.FAILED,
                output = RecipeImportWork.Outcome.Failed(RecipeError.NoApiKey, "import-1").toData()
            )
        )

        assertEquals(
            ImportState.Failed(RecipeError.NoApiKey),
            monitor.importState("import-1").first()
        )
    }

    @Test
    fun `importState of an unknown import is cancelled`() = runTest {
        val monitor = createMonitor()
        assertEquals(ImportState.Cancelled, monitor.importState("nope").first())
    }
}
