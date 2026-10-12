package com.lionotter.recipes.imports

import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import com.lionotter.recipes.data.local.PendingImportStatus
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.worker.ImportInputStore
import com.lionotter.recipes.worker.ImportKind
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.RecipeWorkScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ImportQueueTest {

    private val dao = FakePendingImportDao()
    private val work = FakeImportWork()
    private val inputStore: ImportInputStore = mockk(relaxed = true)
    private val scheduler: RecipeWorkScheduler = mockk {
        every { buildUrlImportRequest(any(), any()) } answers { OneTimeWorkRequestBuilder<RecipeImportWorker>().build() }
        coEvery { enqueueImport(any(), any()) } answers {
            val request = secondArg<OneTimeWorkRequest>()
            work.assignUniqueName(RecipeWorkScheduler.importWorkName(firstArg()), request.id)
            work.put(workInfo(request.id, WorkInfo.State.ENQUEUED))
        }
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun TestScope.createQueue(scope: CoroutineScope = testAppScope()): ImportQueue {
        val repository = PendingImportRepository(dao)
        return ImportQueue(
            workManager = work.workManager,
            pendingImportRepository = repository,
            appScope = scope,
            scheduler = scheduler,
            inputStore = inputStore,
            importPaprikaUseCase = mockk(relaxed = true),
            monitor = ImportMonitor(work.workManager, repository, scope)
        )
    }

    @Test
    fun `startUrlImport inserts a pending row pointing at the enqueued work`() = runTest {
        val queue = createQueue()

        val started = queue.startUrlImport("https://example.com/r")

        val row = dao[started.importId]!!
        assertEquals(PendingImportStatus.PENDING, row.status)
        assertEquals(started.workId.toString(), row.workManagerId)
        assertEquals("https://example.com/r", row.url)
        assertNull("The URL is shown until the page title is known", row.name)
        assertEquals(WorkInfo.State.ENQUEUED, work[started.workId]?.state)
    }

    @Test
    fun `startUrlImport returns the existing import while its work is active`() = runTest {
        val queue = createQueue()

        val first = queue.startUrlImport("https://example.com/r")
        val second = queue.startUrlImport("https://example.com/r")

        assertEquals(first, second)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `startUrlImport does not dedupe against pruned work`() = runTest {
        val queue = createQueue()
        val first = queue.startUrlImport("https://example.com/r")
        work.remove(first.workId) // WorkManager pruned the finished work

        val second = queue.startUrlImport("https://example.com/r")

        assertNotEquals(first.importId, second.importId)
        assertEquals(PendingImportStatus.FAILED, dao[first.importId]!!.status)
        assertEquals(PendingImportStatus.PENDING, dao[second.importId]!!.status)
    }

    @Test
    fun `startUrlImport does not dedupe against finished work`() = runTest {
        val queue = createQueue()
        val first = queue.startUrlImport("https://example.com/r")
        work.put(workInfo(first.workId, WorkInfo.State.SUCCEEDED))

        val second = queue.startUrlImport("https://example.com/r")

        assertNotEquals(first.importId, second.importId)
        advanceUntilIdle()
        assertNull(dao[first.importId])
    }

    @Test
    fun `concurrent startUrlImport calls start one import`() = runTest {
        val queue = createQueue()

        val results = (1..3).map { async { queue.startUrlImport("https://example.com/r") } }.awaitAll()

        assertEquals(1, results.map { it.importId }.distinct().size)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `startup reconciles rows whose work is missing or finished`() = runTest {
        val missingWork = UUID.randomUUID()
        val succeededWork = UUID.randomUUID()
        val runningWork = UUID.randomUUID()
        work.put(workInfo(succeededWork, WorkInfo.State.SUCCEEDED))
        work.put(workInfo(runningWork, WorkInfo.State.RUNNING))
        dao.rows.value = listOf(
            pendingImport("missing", missingWork.toString()),
            pendingImport("succeeded", succeededWork.toString()),
            pendingImport("running", runningWork.toString()),
            pendingImport("never-enqueued", null)
        )

        createQueue()
        advanceUntilIdle()

        assertEquals(PendingImportStatus.FAILED, dao["missing"]!!.status)
        assertEquals(ImportMonitor.INTERRUPTED_MESSAGE, dao["missing"]!!.errorMessage)
        assertEquals(PendingImportStatus.FAILED, dao["never-enqueued"]!!.status)
        assertNull(dao["succeeded"])
        assertEquals(PendingImportStatus.PENDING, dao["running"]!!.status)
    }

    @Test
    fun `cancelImport removes the row and cancels its work`() = runTest {
        val workId = UUID.randomUUID()
        dao.rows.value = listOf(pendingImport("import-1", workId.toString()))
        val queue = createQueue()

        queue.cancelImport("import-1")
        advanceUntilIdle()

        assertTrue(dao.rows.value.isEmpty())
        verify { work.workManager.cancelUniqueWork(RecipeWorkScheduler.importWorkName("import-1")) }
        verify { work.workManager.cancelWorkById(workId) }
    }

    @Test
    fun `cancelImport without a work ID still removes the row`() = runTest {
        dao.rows.value = listOf(pendingImport("import-1", null, status = PendingImportStatus.FAILED))
        val queue = createQueue()

        queue.cancelImport("import-1")
        advanceUntilIdle()

        assertTrue(dao.rows.value.isEmpty())
        verify(exactly = 0) { work.workManager.cancelWorkById(any()) }
    }

    @Test
    fun `retryImport re-enqueues a failed URL import under the same ID`() = runTest {
        val oldWork = UUID.randomUUID()
        dao.rows.value = listOf(
            pendingImport("import-1", oldWork.toString(), status = PendingImportStatus.FAILED, errorMessage = "boom")
        )
        every { inputStore.hasImport("import-1") } returns false
        val queue = createQueue()

        queue.retryImport("import-1")
        advanceUntilIdle()

        val row = dao["import-1"]!!
        assertEquals(PendingImportStatus.PENDING, row.status)
        assertNull(row.errorMessage)
        assertNotEquals(oldWork.toString(), row.workManagerId)
        assertEquals(WorkInfo.State.ENQUEUED, work[UUID.fromString(row.workManagerId)]?.state)
    }

    @Test
    fun `retryImport of a file import without its files cannot be retried`() = runTest {
        dao.rows.value = listOf(
            pendingImport("import-1", UUID.randomUUID().toString(), url = "", status = PendingImportStatus.FAILED)
        )
        every { inputStore.hasImport("import-1") } returns false
        val queue = createQueue()

        queue.retryImport("import-1")
        advanceUntilIdle()

        assertEquals(PendingImportStatus.FAILED, dao["import-1"]!!.status)
        assertEquals("This import can't be retried. Please start it again.", dao["import-1"]!!.errorMessage)
    }

    @Test
    fun `startZipImport records the kind and selection before inserting the row`() = runTest {
        every { inputStore.hasSource("zip-1") } returns true
        every { scheduler.buildZipImportRequest("zip-1") } returns
            OneTimeWorkRequestBuilder<RecipeImportWorker>().build()
        val queue = createQueue()

        val started = queue.startZipImport("zip-1", setOf("a"), "Importing 1 recipe")

        verify { inputStore.writeKind("zip-1", ImportKind.ZIP) }
        verify { inputStore.writeSelection("zip-1", setOf("a")) }
        assertEquals("Importing 1 recipe", dao["zip-1"]!!.name)
        assertEquals(started.workId.toString(), dao["zip-1"]!!.workManagerId)
    }
}
