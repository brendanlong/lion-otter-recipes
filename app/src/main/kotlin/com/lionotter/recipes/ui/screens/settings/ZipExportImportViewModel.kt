package com.lionotter.recipes.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.WorkStatus
import com.lionotter.recipes.worker.WorkTracker
import com.lionotter.recipes.worker.ZipExportWork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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

    private val exportStatus = exportWork.status.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The export's result, reported once (even if it finished while the screen was gone). */
    private val _events = Channel<UserMessage>(Channel.BUFFERED)
    val events: Flow<UserMessage> = _events.receiveAsFlow()

    val isExporting: StateFlow<Boolean> = exportStatus
        .map { status ->
            status == WorkStatus.Missing || status == WorkStatus.Queued || status is WorkStatus.Running
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)

    init {
        viewModelScope.launch {
            exportStatus.collect { status ->
                val message = status?.let(::resultMessage) ?: return@collect
                exportWork.clear()
                _events.send(message)
            }
        }
    }

    fun exportToZip(fileUri: Uri) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { exportWork.start(recipeWorkScheduler.prepareExport(fileUri)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start export", e)
                _events.send(UserMessage(R.string.error_start_export))
            }
        }
    }

    /** The message for finished (or cancelled) export work, or null while it is still running. */
    private fun resultMessage(status: WorkStatus<ZipExportWork.Progress, ZipExportWork.Outcome>): UserMessage? =
        when (status) {
            WorkStatus.Missing, WorkStatus.Queued, is WorkStatus.Running -> null
            is WorkStatus.Finished -> when (val outcome = status.outcome) {
                is ZipExportWork.Outcome.Success -> if (outcome.failedCount > 0) {
                    UserMessage.Plural(
                        R.plurals.exported_recipes_with_failures,
                        outcome.exportedCount,
                        listOf(outcome.exportedCount, outcome.failedCount)
                    )
                } else {
                    UserMessage.Plural(R.plurals.exported_recipes, outcome.exportedCount)
                }
                is ZipExportWork.Outcome.Failed -> outcome.error.toUserMessage()
            }
            WorkStatus.Cancelled -> UserMessage(R.string.error_export_cancelled)
        }
}
