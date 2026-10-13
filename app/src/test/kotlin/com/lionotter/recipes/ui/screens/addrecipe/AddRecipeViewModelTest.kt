package com.lionotter.recipes.ui.screens.addrecipe

import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.R
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.PendingImportRepository
import com.lionotter.recipes.imports.FakeImportWork
import com.lionotter.recipes.imports.FakePendingImportDao
import com.lionotter.recipes.imports.ImportMonitor
import com.lionotter.recipes.imports.ImportQueue
import com.lionotter.recipes.imports.ImportStage
import com.lionotter.recipes.imports.pendingImport
import com.lionotter.recipes.imports.testAppScope
import com.lionotter.recipes.imports.workInfo
import com.lionotter.recipes.worker.RecipeImportWork
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.RecipeWorkScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AddRecipeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dao = FakePendingImportDao()
    private val work = FakeImportWork()
    private val settingsDataStore: SettingsDataStore = mockk {
        every { anthropicApiKey } returns flowOf("sk-ant-test")
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
            inputStore = mockk(relaxed = true),
            importPaprikaUseCase = mockk(),
            monitor = monitor
        )
        return AddRecipeViewModel(handle, queue, monitor, settingsDataStore)
    }

    private fun startedWorkId(): UUID = UUID.fromString(dao.rows.value.single().workManagerId)

    @Test
    fun `invalid URL shows an error without starting an import`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.onUrlChange("not a url")

        viewModel.importRecipe()
        advanceUntilIdle()

        assertEquals(AddRecipeUiState.Error(UserMessage(R.string.error_url_scheme)), viewModel.uiState.value)
        assertEquals(emptyList<Any>(), dao.rows.value)
    }

    @Test
    fun `progress and the new recipe are shown`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.onUrlChange("https://example.com/r")
        viewModel.importRecipe()
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
        viewModel.onUrlChange("https://example.com/r")
        viewModel.importRecipe()
        advanceUntilIdle()

        work.put(workInfo(startedWorkId(), WorkInfo.State.SUCCEEDED))
        advanceUntilIdle()

        assertEquals(AddRecipeEvent.RecipeAdded(null), viewModel.events.first())
        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `missing API key is reported`() = runTest {
        val viewModel = createViewModel(testAppScope())
        viewModel.onUrlChange("https://example.com/r")
        viewModel.importRecipe()
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
        viewModel.onUrlChange("https://example.com/r")
        viewModel.importRecipe()
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
        viewModel.onUrlChange("https://example.com/r")
        viewModel.importRecipe()
        advanceUntilIdle()

        viewModel.cancelImport()
        advanceUntilIdle()

        assertEquals(AddRecipeUiState.Idle, viewModel.uiState.value)
        assertEquals(emptyList<Any>(), dao.rows.value)
    }
}
