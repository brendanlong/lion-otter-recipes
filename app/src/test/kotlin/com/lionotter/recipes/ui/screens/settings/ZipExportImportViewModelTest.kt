package com.lionotter.recipes.ui.screens.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.lionotter.recipes.worker.PendingWork
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.WorkStatus
import com.lionotter.recipes.worker.ZipExportWork
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ZipExportImportViewModelTest {

    private val workId = UUID.randomUUID()
    private val status = MutableStateFlow<WorkStatus<ZipExportWork.Progress, ZipExportWork.Outcome>>(WorkStatus.Missing)
    private val fileUri = mockk<Uri>()
    private val scheduler: RecipeWorkScheduler = mockk {
        every { prepareExport(fileUri) } returns PendingWork(workId) { status.value = WorkStatus.Queued }
        every { observeExport(workId) } returns status
    }

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `export progress and result are shown`() = runTest {
        val viewModel = ZipExportImportViewModel(SavedStateHandle(), scheduler)

        viewModel.exportToZip(fileUri)
        advanceUntilIdle()
        assertEquals(ZipOperationState.Exporting, viewModel.operationState.value)

        status.value = WorkStatus.Finished(ZipExportWork.Outcome.Success(exportedCount = 3, failedCount = 0))
        advanceUntilIdle()
        assertEquals(ZipOperationState.ExportComplete(3, 0), viewModel.operationState.value)

        viewModel.resetOperationState()
        advanceUntilIdle()
        assertEquals(ZipOperationState.Idle, viewModel.operationState.value)
    }

    @Test
    fun `failure to start the export is shown`() = runTest {
        every { scheduler.prepareExport(fileUri) } returns PendingWork(workId) { throw IOException("disk full") }
        val viewModel = ZipExportImportViewModel(SavedStateHandle(), scheduler)

        viewModel.exportToZip(fileUri)
        advanceUntilIdle()

        val state = viewModel.operationState.value
        assertTrue(state is ZipOperationState.Error && state.message.contains("disk full"))
    }

    @Test
    fun `a running export is shown again after process death`() = runTest {
        status.value = WorkStatus.Running(ZipExportWork.Progress.Exporting("Soup", 1, 3))

        val viewModel = ZipExportImportViewModel(SavedStateHandle(mapOf("export_work_id" to workId.toString())), scheduler)
        advanceUntilIdle()

        assertEquals(ZipOperationState.Exporting, viewModel.operationState.value)
    }
}
