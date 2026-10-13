package com.lionotter.recipes.ui.screens.settings

import org.junit.Assert.assertFalse
import kotlinx.coroutines.flow.first
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.R
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
    fun `export progress is shown and the result reported once`() = runTest {
        val viewModel = ZipExportImportViewModel(SavedStateHandle(), scheduler)

        viewModel.exportToZip(fileUri)
        advanceUntilIdle()
        assertTrue(viewModel.isExporting.value)

        status.value = WorkStatus.Finished(ZipExportWork.Outcome.Success(exportedCount = 3, failedCount = 0))
        advanceUntilIdle()
        assertEquals(UserMessage.Plural(R.plurals.exported_recipes, 3), viewModel.events.first())
        assertFalse(viewModel.isExporting.value)
    }

    @Test
    fun `failure to start the export is reported without the exception message`() = runTest {
        every { scheduler.prepareExport(fileUri) } returns PendingWork(workId) { throw IOException("disk full") }
        val viewModel = ZipExportImportViewModel(SavedStateHandle(), scheduler)

        viewModel.exportToZip(fileUri)
        advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_start_export), viewModel.events.first())
        assertFalse(viewModel.isExporting.value)
    }

    @Test
    fun `a failed export reports its error`() = runTest {
        val viewModel = ZipExportImportViewModel(SavedStateHandle(), scheduler)
        viewModel.exportToZip(fileUri)
        advanceUntilIdle()

        status.value = WorkStatus.Finished(
            ZipExportWork.Outcome.Failed(RecipeError.Archive(ArchiveFailure.NOTHING_TO_EXPORT))
        )
        advanceUntilIdle()

        assertEquals(UserMessage(R.string.error_archive_nothing_to_export), viewModel.events.first())
    }

    @Test
    fun `a running export is shown again after process death`() = runTest {
        status.value = WorkStatus.Running(ZipExportWork.Progress.Exporting("Soup", 1, 3))

        val viewModel = ZipExportImportViewModel(SavedStateHandle(mapOf("export_work_id" to workId.toString())), scheduler)
        advanceUntilIdle()

        assertTrue(viewModel.isExporting.value)
    }
}
