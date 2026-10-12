package com.lionotter.recipes.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.WorkStatus
import com.lionotter.recipes.worker.WorkTracker
import com.lionotter.recipes.worker.ZipExportWork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ZipExportImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeWorkScheduler: RecipeWorkScheduler
) : ViewModel() {

    companion object {
        private const val TAG = "ZipExportImportViewModel"
        private const val KEY_EXPORT_WORK_ID = "export_work_id"
    }

    private val exportWork =
        WorkTracker(savedStateHandle, KEY_EXPORT_WORK_ID, viewModelScope, recipeWorkScheduler::observeExport)

    /** Failure to start the export; the export's own state is shown once it is tracked. */
    private val startError = MutableStateFlow<ZipOperationState?>(null)

    val operationState: StateFlow<ZipOperationState> =
        combine(startError, exportWork.status) { error, status ->
            error ?: status?.let(::exportState) ?: ZipOperationState.Idle
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ZipOperationState.Idle
        )

    fun exportToZip(fileUri: Uri) {
        startError.value = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { exportWork.start(recipeWorkScheduler.prepareExport(fileUri)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start export", e)
                startError.value = ZipOperationState.Error("Export failed: ${e.message}")
            }
        }
    }

    fun resetOperationState() {
        startError.value = null
        exportWork.clear()
    }

    private fun exportState(status: WorkStatus<ZipExportWork.Progress, ZipExportWork.Outcome>): ZipOperationState =
        when (status) {
            WorkStatus.Missing, WorkStatus.Queued, is WorkStatus.Running -> ZipOperationState.Exporting
            is WorkStatus.Finished -> when (val outcome = status.outcome) {
                is ZipExportWork.Outcome.Success -> ZipOperationState.ExportComplete(outcome.exportedCount, outcome.failedCount)
                is ZipExportWork.Outcome.Failed -> ZipOperationState.Error(outcome.message ?: "Export failed")
            }
            WorkStatus.Cancelled -> ZipOperationState.Error("Export was cancelled")
        }
}

sealed class ZipOperationState {
    object Idle : ZipOperationState()
    object Exporting : ZipOperationState()
    data class ExportComplete(val exportedCount: Int, val failedCount: Int) : ZipOperationState()
    data class Error(val message: String) : ZipOperationState()
}
