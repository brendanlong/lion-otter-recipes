package com.lionotter.recipes.imports

import android.net.Uri
import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.data.local.PendingImportStatus
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.domain.usecase.ParseHtmlUseCase
import com.lionotter.recipes.domain.usecase.paprika.PaprikaBatch
import com.lionotter.recipes.domain.usecase.paprika.PaprikaImportSteps
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeImportDebugLog
import com.lionotter.recipes.testutil.FakePageContentExtractor
import com.lionotter.recipes.testutil.FakePageFetcher
import com.lionotter.recipes.testutil.FakePaprikaExportReader
import com.lionotter.recipes.testutil.FakeRecipeBatchParser
import com.lionotter.recipes.testutil.FakeRecipeParser
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.util.AppJson
import com.lionotter.recipes.worker.ImportInputStore
import com.lionotter.recipes.worker.ImportKind
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.RecipeWorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ImportQueueTest {

    private val dao = FakePendingImportDao()
    private val work = FakeImportWork()
    private val inputStore: ImportInputStore = mockk(relaxed = true)
    private val batchParser = FakeRecipeBatchParser()
    private val settings = FakeAiSettings()
    private val repository = FakeRecipeRepository()
    private val paprikaSteps = PaprikaImportSteps(
        paprikaReader = FakePaprikaExportReader(emptyList()),
        parseHtmlUseCase = ParseHtmlUseCase(
            FakeRecipeParser(), FakePageContentExtractor(), repository, FakeImportDebugLog(), settings, FakeImageStore()
        ),
        recipeBatchParser = batchParser,
        aiSettings = settings,
        pageFetcher = FakePageFetcher(),
        imageStore = FakeImageStore(),
        recipeRepository = repository,
        json = AppJson,
        ioDispatcher = Dispatchers.Unconfined
    )

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Fails every check with [checkError] if set; records the checked files. */
    private val fileChecker = object : ImportFileChecker {
        var checkError: RecipeErrorException? = null
        val checked = mutableListOf<Pair<File, RecipeFileFormat>>()

        override suspend fun check(file: File, format: RecipeFileFormat) {
            checked += file to format
            checkError?.let { throw it }
        }
    }
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
            paprikaImportSteps = paprikaSteps,
            monitor = ImportMonitor(work.workManager, repository, scope),
            fileChecker = fileChecker
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
    fun `a captured page is moved into the import before the row is inserted`() = runTest {
        val queue = createQueue()

        val started = queue.startUrlImport("https://example.com/r", pageHtmlPath = "/work-inputs/page.txt")

        verify { inputStore.adoptPageHtml(started.importId, "/work-inputs/page.txt") }
        verify { inputStore.writeKind(started.importId, ImportKind.URL) }
        assertEquals("https://example.com/r", dao[started.importId]!!.url)
    }

    @Test
    fun `a captured page for an import already running is deleted`() = runTest {
        val queue = createQueue()
        val first = queue.startUrlImport("https://example.com/r")

        val second = queue.startUrlImport("https://example.com/r", pageHtmlPath = "/work-inputs/page.txt")

        assertEquals(first, second)
        verify { inputStore.deleteWorkText("/work-inputs/page.txt") }
        verify(exactly = 0) { inputStore.adoptPageHtml(any(), any()) }
    }

    @Test
    fun `a captured page that can't be staged leaves no row and no files`() = runTest {
        every { inputStore.adoptPageHtml(any(), any()) } throws java.io.IOException("gone")
        val queue = createQueue()

        val failure = runCatching { queue.startUrlImport("https://example.com/r", pageHtmlPath = "/work-inputs/page.txt") }

        assertTrue(failure.isFailure)
        assertEquals(emptyList<Any>(), dao.rows.value)
        verify { inputStore.deleteImport(any()) }
        verify { inputStore.deleteWorkText("/work-inputs/page.txt") }
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
        assertEquals(RecipeError.Interrupted, dao["missing"]!!.error)
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
    fun `cancelling a Paprika import cancels its batch once, even after the worker did`() = runTest {
        val dir = tempFolder.newFolder("import-1")
        paprikaSteps.store(dir).writeBatch(
            PaprikaBatch("batch-1", "model", thinkingEnabled = false, submittedAtMillis = 0, recipes = emptyList())
        )
        every { inputStore.hasImport("import-1") } returns true
        every { inputStore.readKind("import-1") } returns ImportKind.PAPRIKA
        every { inputStore.importDir("import-1") } returns dir
        coEvery { inputStore.withImportLock<Unit>("import-1", any()) } returns Unit
        dao.rows.value = listOf(pendingImport("import-1", UUID.randomUUID().toString()))
        val queue = createQueue()

        queue.cancelImport("import-1")
        advanceUntilIdle()
        assertEquals(listOf("batch-1"), batchParser.cancelled)

        // The worker (API 31+) cancelled first: the queue doesn't cancel again
        queue.cancelImport("import-1")
        advanceUntilIdle()
        assertEquals(listOf("batch-1"), batchParser.cancelled)
        verify(exactly = 2) { inputStore.deleteImport("import-1") }
    }

    @Test
    fun `stopping background work cancels every import but keeps the queue`() = runTest {
        val workId = UUID.randomUUID()
        dao.rows.value = listOf(pendingImport("import-1", workId.toString()))
        val queue = createQueue()

        queue.stopBackgroundWork()

        verify { work.workManager.cancelUniqueWork(RecipeWorkScheduler.importWorkName("import-1")) }
        verify { work.workManager.cancelWorkById(workId) }
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `clearing user data cancels every import and deletes the queue and its files`() = runTest {
        val runningWork = UUID.randomUUID()
        work.put(workInfo(runningWork, WorkInfo.State.RUNNING))
        stageZipImport("zip-1")
        dao.rows.value = listOf(
            pendingImport("url-1", runningWork.toString()),
            pendingImport("zip-1", null, url = "", status = PendingImportStatus.FAILED),
        )
        val queue = createQueue()

        queue.clearUserData()

        assertTrue(dao.rows.value.isEmpty())
        verify { work.workManager.cancelWorkById(runningWork) }
        verify { work.workManager.cancelUniqueWork(RecipeWorkScheduler.importWorkName("url-1")) }
        verify { work.workManager.cancelUniqueWork(RecipeWorkScheduler.importWorkName("zip-1")) }
        verify { inputStore.deleteImport("zip-1") }
        verify { inputStore.deleteAllImports() }
    }

    @Test
    fun `an import that can't be cleaned up doesn't stop the rest`() = runTest {
        stageZipImport("zip-1")
        every { inputStore.deleteImport("zip-1") } throws IllegalStateException("disk error")
        dao.rows.value = listOf(
            pendingImport("zip-1", null, url = ""),
            pendingImport("url-1", UUID.randomUUID().toString()),
        )
        val queue = createQueue()

        val result = runCatching { queue.clearUserData() }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(dao.rows.value.isEmpty())
        verify { inputStore.deleteAllImports() }
    }

    private fun stageZipImport(id: String) {
        every { inputStore.hasImport(id) } returns true
        every { inputStore.readKind(id) } returns ImportKind.ZIP
        coEvery { inputStore.withImportLock<Unit>(id, any()) } returns Unit
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
            pendingImport("import-1", oldWork.toString(), status = PendingImportStatus.FAILED, error = RecipeError.Unexpected)
        )
        every { inputStore.hasImport("import-1") } returns false
        val queue = createQueue()

        queue.retryImport("import-1")
        advanceUntilIdle()

        val row = dao["import-1"]!!
        assertEquals(PendingImportStatus.PENDING, row.status)
        assertNull(row.error)
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
        assertEquals(RecipeError.CannotRetry, dao["import-1"]!!.error)
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

    private val pickedUri: Uri = mockk()
    private val markdownFile = RecipeFile(pickedUri, "Bread.md", RecipeFileFormat.TEXT)

    private fun stubCopiedFile(content: String) {
        val copy = tempFolder.newFile().apply { writeText(content) }
        coEvery { inputStore.copySource(any(), pickedUri, any(), any()) } returns copy
        every { scheduler.buildRecipeFileImportRequest(any()) } answers
            { OneTimeWorkRequestBuilder<RecipeImportWorker>().build() }
    }

    @Test
    fun `startRecipeFileImport copies the file within its limit before inserting the row`() = runTest {
        stubCopiedFile("# Bread")
        val queue = createQueue()

        val started = queue.startRecipeFileImport(listOf(markdownFile))

        coVerify { inputStore.copySource(started.importId, pickedUri, RecipeFileFormat.TEXT.maxBytes, 0) }
        verify { inputStore.writeKind(started.importId, ImportKind.TEXT_FILE) }
        val row = dao[started.importId]!!
        assertEquals("Bread.md", row.name)
        assertEquals("", row.url)
        assertEquals(started.workId.toString(), row.workManagerId)
        assertEquals(WorkInfo.State.ENQUEUED, work[started.workId]?.state)
    }

    @Test
    fun `an empty recipe file is rejected and leaves nothing behind`() = runTest {
        stubCopiedFile("")
        val queue = createQueue()

        val error = assertRecipeError { queue.startRecipeFileImport(listOf(markdownFile)) }

        assertEquals(RecipeError.ImportFile(ImportFileFailure.EMPTY), error)
        assertTrue(dao.rows.value.isEmpty())
        verify { inputStore.deleteImport(any()) }
    }

    @Test
    fun `a recipe file over its limit is rejected with the limit`() = runTest {
        val tooLarge = RecipeError.ImportFile(ImportFileFailure.TOO_LARGE, RecipeFileFormat.TEXT.maxBytes)
        coEvery { inputStore.copySource(any(), pickedUri, any(), any()) } throws RecipeErrorException(tooLarge)
        val queue = createQueue()

        assertEquals(tooLarge, assertRecipeError { queue.startRecipeFileImport(listOf(markdownFile)) })
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `a recipe file that can't be read is reported as unreadable`() = runTest {
        coEvery { inputStore.copySource(any(), pickedUri, any(), any()) } throws SecurityException("no permission")
        val queue = createQueue()

        val error = assertRecipeError { queue.startRecipeFileImport(listOf(markdownFile)) }

        assertEquals(RecipeError.ImportFile(ImportFileFailure.UNREADABLE), error)
        verify { inputStore.deleteImport(any()) }
    }

    @Test
    fun `retryImport of a recipe file import re-enqueues it from its copied file`() = runTest {
        dao.rows.value = listOf(
            pendingImport("file-1", UUID.randomUUID().toString(), url = "", status = PendingImportStatus.FAILED)
        )
        every { inputStore.hasImport("file-1") } returns true
        every { inputStore.readKind("file-1") } returns ImportKind.HTML_FILE
        every { scheduler.buildRecipeFileImportRequest("file-1") } returns
            OneTimeWorkRequestBuilder<RecipeImportWorker>().build()
        val queue = createQueue()

        queue.retryImport("file-1")
        advanceUntilIdle()

        verify { scheduler.buildRecipeFileImportRequest("file-1") }
        assertEquals(PendingImportStatus.PENDING, dao["file-1"]!!.status)
    }

    private fun stubCopiedPdf(): File {
        val copy = tempFolder.newFile().apply { writeText("%PDF-1.4") }
        coEvery { inputStore.copySource(any(), pickedUri, any(), any()) } returns copy
        every { scheduler.buildRecipeFileImportRequest(any()) } answers
            { OneTimeWorkRequestBuilder<RecipeImportWorker>().build() }
        return copy
    }

    private val pdfFile = RecipeFile(pickedUri, "Bread.pdf", RecipeFileFormat.PDF)

    @Test
    fun `a PDF is checked once copied, before the row is inserted`() = runTest {
        val copy = stubCopiedPdf()
        val queue = createQueue()

        val started = queue.startRecipeFileImport(listOf(pdfFile))

        coVerify { inputStore.copySource(started.importId, pickedUri, ResourceLimits.MAX_PDF_FILE_BYTES, 0) }
        assertEquals(listOf(copy to RecipeFileFormat.PDF), fileChecker.checked)
        verify { inputStore.writeKind(started.importId, ImportKind.PDF_FILE) }
        assertEquals("Bread.pdf", dao[started.importId]!!.name)
    }

    @Test
    fun `a file that fails its check is rejected and leaves nothing behind`() = runTest {
        stubCopiedPdf()
        val tooManyPages = RecipeError.ImportFile(ImportFileFailure.TOO_MANY_PAGES, limit = ResourceLimits.MAX_PDF_PAGES)
        fileChecker.checkError = RecipeErrorException(tooManyPages)
        val queue = createQueue()

        assertEquals(tooManyPages, assertRecipeError { queue.startRecipeFileImport(listOf(pdfFile)) })
        assertTrue(dao.rows.value.isEmpty())
        verify { inputStore.deleteImport(any()) }
    }

    @Test
    fun `several photos are copied in order into one import`() = runTest {
        val photos = (1..3).map { RecipeFile(mockk(), "page$it.jpg", RecipeFileFormat.IMAGE) }
        coEvery { inputStore.copySource(any(), any(), any(), any()) } answers
            { tempFolder.newFile().apply { writeBytes(byteArrayOf(1)) } }
        every { scheduler.buildRecipeFileImportRequest(any()) } answers
            { OneTimeWorkRequestBuilder<RecipeImportWorker>().build() }
        val queue = createQueue()

        val started = queue.startRecipeFileImport(photos)

        photos.forEachIndexed { index, photo ->
            coVerify { inputStore.copySource(started.importId, photo.uri, ResourceLimits.MAX_PHOTO_FILE_BYTES, index) }
        }
        verify { inputStore.writeKind(started.importId, ImportKind.IMAGE_FILE) }
        assertEquals("page1.jpg", dao[started.importId]!!.name)
    }

    @Test
    fun `several files that aren't all photos, or too many photos, are rejected before anything is copied`() = runTest {
        val photo = RecipeFile(mockk(), "page.jpg", RecipeFileFormat.IMAGE)
        val queue = createQueue()

        assertEquals(
            RecipeError.ImportFile(ImportFileFailure.NOT_ALL_PHOTOS),
            assertRecipeError { queue.startRecipeFileImport(listOf(photo, pdfFile)) }
        )
        assertEquals(
            RecipeError.ImportFile(ImportFileFailure.TOO_MANY_FILES, limit = ResourceLimits.MAX_PHOTOS_PER_RECIPE),
            assertRecipeError { queue.startRecipeFileImport(List(ResourceLimits.MAX_PHOTOS_PER_RECIPE + 1) { photo }) }
        )
        coVerify(exactly = 0) { inputStore.copySource(any(), any(), any(), any()) }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `retryImport of a photo import re-enqueues it from its copied files`() = runTest {
        dao.rows.value = listOf(
            pendingImport("photos-1", UUID.randomUUID().toString(), url = "", status = PendingImportStatus.FAILED)
        )
        every { inputStore.hasImport("photos-1") } returns true
        every { inputStore.readKind("photos-1") } returns ImportKind.IMAGE_FILE
        every { scheduler.buildRecipeFileImportRequest("photos-1") } returns
            OneTimeWorkRequestBuilder<RecipeImportWorker>().build()
        val queue = createQueue()

        queue.retryImport("photos-1")
        advanceUntilIdle()

        assertEquals(PendingImportStatus.PENDING, dao["photos-1"]!!.status)
    }

    private suspend fun assertRecipeError(block: suspend () -> Unit): RecipeError {
        try {
            block()
        } catch (e: RecipeErrorException) {
            return e.recipeError
        }
        throw AssertionError("Expected a RecipeErrorException")
    }
}
