package com.lionotter.recipes.ui.screens.addrecipe

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import com.lionotter.recipes.R
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.imports.FakeImportWork
import com.lionotter.recipes.imports.FakePendingImportDao
import com.lionotter.recipes.imports.ImportFileInspector
import com.lionotter.recipes.imports.ImportMonitor
import com.lionotter.recipes.imports.ImportQueue
import com.lionotter.recipes.imports.ImportStage
import com.lionotter.recipes.imports.RecipeFile
import com.lionotter.recipes.imports.pendingImport
import com.lionotter.recipes.imports.testAppScope
import com.lionotter.recipes.imports.workInfo
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.ui.screens.importbrowser.BrowsedPage
import com.lionotter.recipes.ui.screens.importselection.ImportType
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.worker.ImportInputStore
import com.lionotter.recipes.worker.ImportKind
import com.lionotter.recipes.worker.RecipeImportWork
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.RecipeWorkScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Uri and route arguments
class AddRecipeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dao = FakePendingImportDao()
    private val work = FakeImportWork()
    private val aiSettings = FakeAiSettings()

    /** Picked files the inspector recognizes as recipe files; anything else is an archive. */
    private val recipeFiles = mutableMapOf<Uri, RecipeFile>()
    private val inspectedTypes = mutableListOf<String?>()
    private val inspector = ImportFileInspector { uri, mimeType ->
        inspectedTypes += mimeType
        recipeFiles[uri]
    }
    private val pickedUri: Uri = Uri.parse("content://files/bread.md")
    private val inputStore: ImportInputStore = mockk(relaxed = true)
    private val scheduler: RecipeWorkScheduler = mockk {
        every { buildUrlImportRequest(any(), any()) } answers { OneTimeWorkRequestBuilder<RecipeImportWorker>().build() }
        every { buildRecipeFileImportRequest(any()) } answers { OneTimeWorkRequestBuilder<RecipeImportWorker>().build() }
        coEvery { enqueueImport(any(), any()) } answers {
            val request = secondArg<OneTimeWorkRequest>()
            work.assignUniqueName(RecipeWorkScheduler.importWorkName(firstArg()), request.id)
            work.put(workInfo(request.id, WorkInfo.State.ENQUEUED))
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun createViewModel(scope: CoroutineScope, handle: SavedStateHandle = SavedStateHandle()): AddRecipeViewModel {
        val repository = PendingImportRepository(dao)
        val monitor = ImportMonitor(work.workManager, repository, scope)
        val queue = ImportQueue(
            workManager = work.workManager,
            pendingImportRepository = repository,
            appScope = scope,
            scheduler = scheduler,
            inputStore = inputStore,
            paprikaImportSteps = mockk(),
            monitor = monitor
        )
        return AddRecipeViewModel(handle, queue, monitor, aiSettings, inspector)
    }

    private fun startedWorkId(): UUID = UUID.fromString(dao.rows.value.single().workManagerId)

    @Test
    fun `invalid URL shows an error without starting an import`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.onUrlChange("not a url")

        viewModel.onImportClick()
        advanceUntilIdle()

        assertEquals(AddRecipeUiState.Error(UserMessage(R.string.error_url_scheme), isUrlError = true), viewModel.uiState.value)
        assertEquals(emptyList<Any>(), dao.rows.value)
    }

    @Test
    fun `progress and the new recipe are shown`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.importPage(BrowsedPage("https://example.com/r", htmlPath = null))
        advanceUntilIdle()
        assertEquals(AddRecipeUiState.Loading(ImportStage.QUEUED), viewModel.uiState.value)

        val workId = startedWorkId()
        work.put(workInfo(workId, WorkInfo.State.RUNNING, progress = RecipeImportWork.Progress.FetchingPage.toData()))
        advanceUntilIdle()
        assertEquals(AddRecipeUiState.Loading(ImportStage.FETCHING_PAGE), viewModel.uiState.value)

        work.put(
            workInfo(
                workId,
                WorkInfo.State.SUCCEEDED,
                output = RecipeImportWork.Outcome.Success("i", "recipe-1", "Pasta").toData()
            )
        )
        advanceUntilIdle()
        assertEquals(AddRecipeEvent.RecipeAdded("recipe-1"), viewModel.events.first())
        // Reported once; the screen is ready for another import
        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `success without a recipe ID does not stay loading`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.importPage(BrowsedPage("https://example.com/r", htmlPath = null))
        advanceUntilIdle()

        work.put(workInfo(startedWorkId(), WorkInfo.State.SUCCEEDED))
        advanceUntilIdle()

        assertEquals(AddRecipeEvent.RecipeAdded(null), viewModel.events.first())
        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `missing API key is reported`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.importPage(BrowsedPage("https://example.com/r", htmlPath = null))
        advanceUntilIdle()

        work.put(
            workInfo(
                startedWorkId(),
                WorkInfo.State.FAILED,
                output = RecipeImportWork.Outcome.Failed(RecipeError.NoApiKey, "i").toData()
            )
        )
        advanceUntilIdle()

        assertEquals(AddRecipeUiState.NoApiKey, viewModel.uiState.value)
    }

    @Test
    fun `an import failure is shown as a localized message`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.importPage(BrowsedPage("https://example.com/r", htmlPath = null))
        advanceUntilIdle()

        val error = RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403)
        work.put(
            workInfo(startedWorkId(), WorkInfo.State.FAILED, output = RecipeImportWork.Outcome.Failed(error, "i").toData())
        )
        advanceUntilIdle()

        assertEquals(AddRecipeUiState.Error(UserMessage(R.string.error_page_blocked, 403)), viewModel.uiState.value)
    }

    @Test
    fun `an import that finished while the app was gone is reported once`() = runTest {
        val workId = UUID.randomUUID()
        work.assignUniqueName(RecipeWorkScheduler.importWorkName("import-1"), workId)
        work.put(
            workInfo(workId, WorkInfo.State.SUCCEEDED, output = RecipeImportWork.Outcome.Success("import-1", "r1", "Pasta").toData())
        )
        val handle = SavedStateHandle(mapOf("import_id" to "import-1"))

        val viewModel = createViewModel(testAppScope(), handle)
        advanceUntilIdle()

        assertEquals(AddRecipeEvent.RecipeAdded("r1"), viewModel.events.first())
        assertEquals(null, handle.get<String>("import_id"))
    }

    @Test
    fun `an import in progress is shown again after process death`() = runTest {
        val workId = UUID.randomUUID()
        dao.rows.value = listOf(pendingImport("import-1", workId.toString()))
        work.assignUniqueName(RecipeWorkScheduler.importWorkName("import-1"), workId)
        work.put(workInfo(workId, WorkInfo.State.RUNNING, progress = RecipeImportWork.Progress.Saving.toData()))

        val viewModel = createViewModel(testAppScope(), SavedStateHandle(mapOf("import_id" to "import-1")))

        assertEquals(
            AddRecipeUiState.Loading(ImportStage.SAVING),
            viewModel.uiState.first { it is AddRecipeUiState.Loading }
        )
    }

    @Test
    fun `cancel returns to idle and removes the import`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.importPage(BrowsedPage("https://example.com/r", htmlPath = null))
        advanceUntilIdle()

        viewModel.cancelImport()
        advanceUntilIdle()

        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
        assertEquals(emptyList<Any>(), dao.rows.value)
    }

    @Test
    fun `a valid URL opens the browser instead of importing`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.onUrlChange("Pasta https://example.com/r")

        viewModel.onImportClick()
        advanceUntilIdle()

        assertEquals(AddRecipeEvent.OpenBrowser("https://example.com/r"), viewModel.events.first())
        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
        assertEquals(emptyList<Any>(), dao.rows.value)
    }

    @Test
    fun `a page captured in the browser is handed to the import`() = runTest {
        val viewModel = createViewModel(testAppScope())

        viewModel.importPage(BrowsedPage("https://example.com/final", htmlPath = "/work-inputs/page.txt"))
        advanceUntilIdle()

        val row = dao.rows.value.single()
        assertEquals("https://example.com/final", row.url)
        verify { inputStore.adoptPageHtml(row.id, "/work-inputs/page.txt") }
        verify { inputStore.writeKind(row.id, ImportKind.URL) }
        assertEquals(AddRecipeUiState.Loading(ImportStage.QUEUED), viewModel.uiState.value)
    }

    private fun pickMarkdown(content: String = "# Bread") {
        recipeFiles[pickedUri] = RecipeFile(pickedUri, "Bread.md", RecipeFileFormat.TEXT)
        coEvery { inputStore.copySource(any(), pickedUri, any()) } returns
            File.createTempFile("picked", ".md").apply {
                deleteOnExit()
                writeText(content)
            }
    }

    @Test
    fun `a picked recipe file is imported and its progress shown`() = runTest {
        pickMarkdown()
        val viewModel = createViewModel(testAppScope())

        viewModel.onFilePicked(pickedUri)
        advanceUntilIdle()

        val row = dao.rows.value.single()
        assertEquals("Bread.md", row.name)
        verify { inputStore.writeKind(row.id, ImportKind.TEXT_FILE) }
        assertEquals(AddRecipeUiState.Loading(ImportStage.QUEUED), viewModel.uiState.value)

        work.put(
            workInfo(
                startedWorkId(),
                WorkInfo.State.SUCCEEDED,
                output = RecipeImportWork.Outcome.Success(row.id, "recipe-1", "Bread").toData()
            )
        )
        advanceUntilIdle()
        assertEquals(AddRecipeEvent.RecipeAdded("recipe-1"), viewModel.events.first())
    }

    @Test
    fun `a picked archive goes to the selection screen`() = runTest {
        val export = Uri.parse("content://downloads/export.paprikarecipes")
        val viewModel = createViewModel(testAppScope())

        viewModel.onFilePicked(export)
        advanceUntilIdle()

        assertEquals(AddRecipeEvent.OpenImportSelection(ImportType.PAPRIKA, export), viewModel.events.first())
        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
        assertEquals(emptyList<Any>(), dao.rows.value)
    }

    @Test
    fun `a recipe file that is too large is reported with the limit`() = runTest {
        pickMarkdown()
        val tooLarge = RecipeError.ImportFile(ImportFileFailure.TOO_LARGE, 200 * 1024L)
        coEvery { inputStore.copySource(any(), pickedUri, any()) } throws RecipeErrorException(tooLarge)
        val viewModel = createViewModel(testAppScope())

        viewModel.onFilePicked(pickedUri)
        advanceUntilIdle()

        assertEquals(
            AddRecipeUiState.Error(
                UserMessage(R.string.error_import_file_too_large_limit, UserMessage(R.string.file_size_kb, 200L))
            ),
            viewModel.uiState.value
        )
        assertEquals(emptyList<Any>(), dao.rows.value)
    }

    @Test
    fun `a recipe file shared with the app is imported once`() = runTest {
        pickMarkdown()
        val handle = SavedStateHandle(mapOf("fileUri" to pickedUri.toString(), "fileMimeType" to "text/markdown"))

        createViewModel(testAppScope(), handle)
        advanceUntilIdle()
        // Recreated, e.g. after rotation: the file isn't imported again
        createViewModel(testAppScope(), handle)
        advanceUntilIdle()

        assertEquals("Bread.md", dao.rows.value.single().name)
        // The sharing app's type is used again, so the file is recognized the same way
        assertEquals(listOf<String?>("text/markdown"), inspectedTypes)
    }

    @Test
    fun `cancelling one start doesn't leak into the next`() = runTest {
        pickMarkdown()
        val viewModel = createViewModel(testAppScope())

        viewModel.onFilePicked(pickedUri)
        viewModel.cancelImport()
        viewModel.importPage(BrowsedPage("https://example.com/r", htmlPath = null))
        advanceUntilIdle()

        // The cancelled file import is removed; the page import keeps running
        assertEquals("https://example.com/r", dao.rows.value.single().url)
        assertEquals(AddRecipeUiState.Loading(ImportStage.QUEUED), viewModel.uiState.value)
    }

    @Test
    fun `cancelling while a picked file is being checked starts nothing`() = runTest {
        pickMarkdown()
        val viewModel = createViewModel(testAppScope())

        viewModel.onFilePicked(pickedUri)
        viewModel.cancelImport()
        advanceUntilIdle()

        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
        assertEquals(emptyList<Any>(), dao.rows.value)
    }
}
