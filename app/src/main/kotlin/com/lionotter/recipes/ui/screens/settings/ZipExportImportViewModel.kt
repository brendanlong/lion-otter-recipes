package com.lionotter.recipes.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.worker.ZipExportWorker
import com.lionotter.recipes.worker.observeWorkByTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ZipExportImportViewModel @Inject constructor(
    private val workManager: WorkManager
) : ViewModel() {

    private val _operationState = MutableStateFlow<ZipOperationState>(ZipOperationState.Idle)
    val operationState: StateFlow<ZipOperationState> = _operationState.asStateFlow()

    private var currentWorkId: UUID? = null

    init {
        observeWorkStatus()
    }

    fun exportToZip(fileUri: Uri) {
        val workRequest = OneTimeWorkRequestBuilder<ZipExportWorker>()
            .setInputData(ZipExportWorker.createInputData(fileUri))
            .addTag(ZipExportWorker.TAG_ZIP_EXPORT)
            .build()

        currentWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _operationState.value = ZipOperationState.Exporting
    }

    fun resetOperationState() {
        _operationState.value = ZipOperationState.Idle
    }

    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(ZipExportWorker.TAG_ZIP_EXPORT) { currentWorkId }
                .collect { handleExportWorkInfo(it) }
        }
    }

    private fun handleExportWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                _operationState.value = ZipOperationState.Exporting
            }
            WorkInfo.State.SUCCEEDED -> {
                val exported = workInfo.outputData.getInt(ZipExportWorker.KEY_EXPORTED_COUNT, 0)
                val failed = workInfo.outputData.getInt(ZipExportWorker.KEY_FAILED_COUNT, 0)
                _operationState.value = ZipOperationState.ExportComplete(exported, failed)
                currentWorkId = null
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(ZipExportWorker.KEY_ERROR_MESSAGE)
                    ?: "Export failed"
                _operationState.value = ZipOperationState.Error(error)
                currentWorkId = null
                workManager.pruneWork()
            }
            else -> {}
        }
    }
}

sealed class ZipOperationState {
    object Idle : ZipOperationState()
    object Exporting : ZipOperationState()
    data class ExportComplete(val exportedCount: Int, val failedCount: Int) : ZipOperationState()
    data class Error(val message: String) : ZipOperationState()
}
