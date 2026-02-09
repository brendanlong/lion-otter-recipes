package com.lionotter.recipes.ui.screens.googledrive

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.ui.screens.settings.components.FolderPickerState
import com.lionotter.recipes.ui.screens.settings.components.FolderSelection
import com.lionotter.recipes.worker.GoogleDriveSyncWorker
import com.lionotter.recipes.worker.observeWorkByTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class GoogleDriveViewModel @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val workManager: WorkManager,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    companion object {
        const val PERIODIC_SYNC_WORK_NAME = "google_drive_periodic_sync"
        private const val DEFAULT_SYNC_FOLDER_NAME = "Lion+Otter Recipes"
    }

    private val _uiState = MutableStateFlow<GoogleDriveUiState>(GoogleDriveUiState.Loading)
    val uiState: StateFlow<GoogleDriveUiState> = _uiState.asStateFlow()

    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    val syncEnabled: StateFlow<Boolean> = settingsDataStore.googleDriveSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val syncFolderName: StateFlow<String?> = settingsDataStore.googleDriveSyncFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastSyncTimestamp: StateFlow<String?> = settingsDataStore.googleDriveLastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _folderPickerState = MutableStateFlow<FolderPickerState?>(null)
    val folderPickerState: StateFlow<FolderPickerState?> = _folderPickerState.asStateFlow()

    private val _showFolderPicker = MutableStateFlow(false)
    val showFolderPicker: StateFlow<Boolean> = _showFolderPicker.asStateFlow()

    private var currentWorkId: UUID? = null

    init {
        checkSignInStatus()
        observeWorkStatus()
    }

    private fun checkSignInStatus() {
        viewModelScope.launch {
            if (googleDriveService.isSignedIn()) {
                val email = googleDriveService.getSignedInEmail()
                _uiState.value = GoogleDriveUiState.SignedIn(email ?: "Unknown")
            } else {
                _uiState.value = GoogleDriveUiState.SignedOut
            }
        }
    }

    /**
     * Refresh the sign-in status. Call this when returning to a screen
     * to ensure the UI reflects the current sign-in state.
     */
    fun refreshSignInStatus() {
        checkSignInStatus()
    }

    fun getSignInIntent(): Intent {
        return googleDriveService.getSignInIntent()
    }

    fun handleSignInResult(account: GoogleSignInAccount?) {
        viewModelScope.launch {
            val success = googleDriveService.handleSignInResult(account)
            if (success) {
                val email = googleDriveService.getSignedInEmail()
                _uiState.value = GoogleDriveUiState.SignedIn(email ?: "Unknown")
            } else {
                _uiState.value = GoogleDriveUiState.Error("Sign in failed - account was null")
            }
        }
    }

    fun handleSignInError(errorMessage: String) {
        _uiState.value = GoogleDriveUiState.Error(errorMessage)
    }

    fun signOut() {
        viewModelScope.launch {
            disableSync()
            googleDriveService.signOut()
            _uiState.value = GoogleDriveUiState.SignedOut
        }
    }

    /**
     * Start the enable sync flow by showing the folder picker dialog.
     */
    fun enableSync() {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        _showFolderPicker.value = true
        _folderPickerState.value = FolderPickerState.Loading

        viewModelScope.launch {
            val result = googleDriveService.listFolders()
            result.fold(
                onSuccess = { folders ->
                    _folderPickerState.value = FolderPickerState.Loaded(folders)
                },
                onFailure = { error ->
                    _folderPickerState.value = FolderPickerState.Error(
                        error.message ?: "Failed to load folders"
                    )
                }
            )
        }
    }

    /**
     * Handle folder selection from the picker dialog and enable sync.
     * Clears the last sync timestamp so the first sync with the new folder
     * treats all remote recipes as new (downloads them instead of deleting).
     */
    fun onFolderSelected(selection: FolderSelection) {
        _showFolderPicker.value = false

        viewModelScope.launch {
            when (selection) {
                is FolderSelection.CreateNew -> {
                    val result = googleDriveService.createFolder(DEFAULT_SYNC_FOLDER_NAME)
                    result.fold(
                        onSuccess = { folder ->
                            settingsDataStore.setGoogleDriveSyncFolder(folder.id, folder.name)
                            settingsDataStore.clearGoogleDriveLastSyncTimestamp()
                            settingsDataStore.setGoogleDriveSyncEnabled(true)
                            schedulePeriodicSync()
                            triggerSync()
                        },
                        onFailure = { error ->
                            _operationState.value = OperationState.Error(
                                "Failed to create folder: ${error.message}"
                            )
                        }
                    )
                }
                is FolderSelection.Existing -> {
                    settingsDataStore.setGoogleDriveSyncFolder(
                        selection.folder.id,
                        selection.folder.name
                    )
                    settingsDataStore.clearGoogleDriveLastSyncTimestamp()
                    settingsDataStore.setGoogleDriveSyncEnabled(true)
                    schedulePeriodicSync()
                    triggerSync()
                }
            }
        }
    }

    /**
     * Dismiss the folder picker dialog without enabling sync.
     */
    fun dismissFolderPicker() {
        _showFolderPicker.value = false
        _folderPickerState.value = null
    }

    /**
     * Show the folder picker to change the sync folder.
     */
    fun changeSyncFolder() {
        enableSync()
    }

    /**
     * Disable sync and cancel periodic work.
     */
    fun disableSync() {
        viewModelScope.launch {
            settingsDataStore.setGoogleDriveSyncEnabled(false)
            workManager.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
        }
    }

    /**
     * Trigger a one-time sync immediately.
     */
    fun triggerSync() {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
            .setInputData(GoogleDriveSyncWorker.createInputData())
            .addTag(GoogleDriveSyncWorker.TAG_DRIVE_SYNC)
            .build()

        currentWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _operationState.value = OperationState.Syncing
    }

    /**
     * Schedule periodic sync every 6 hours.
     */
    private fun schedulePeriodicSync() {
        val periodicWorkRequest = PeriodicWorkRequestBuilder<GoogleDriveSyncWorker>(
            6, TimeUnit.HOURS
        )
            .addTag(GoogleDriveSyncWorker.TAG_DRIVE_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )
    }

    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(GoogleDriveSyncWorker.TAG_DRIVE_SYNC) { currentWorkId }
                .collect { handleSyncWorkInfo(it) }
        }
    }

    private fun handleSyncWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                _operationState.value = OperationState.Syncing
            }
            WorkInfo.State.SUCCEEDED -> {
                val resultType = workInfo.outputData.getString(GoogleDriveSyncWorker.KEY_RESULT_TYPE)
                if (resultType == GoogleDriveSyncWorker.RESULT_SUCCESS) {
                    val uploaded = workInfo.outputData.getInt(GoogleDriveSyncWorker.KEY_UPLOADED_COUNT, 0)
                    val downloaded = workInfo.outputData.getInt(GoogleDriveSyncWorker.KEY_DOWNLOADED_COUNT, 0)
                    val updated = workInfo.outputData.getInt(GoogleDriveSyncWorker.KEY_UPDATED_COUNT, 0)
                    val deleted = workInfo.outputData.getInt(GoogleDriveSyncWorker.KEY_DELETED_COUNT, 0)
                    _operationState.value = OperationState.SyncComplete(uploaded, downloaded, updated, deleted)
                } else {
                    _operationState.value = OperationState.Idle
                }
                currentWorkId = null
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(GoogleDriveSyncWorker.KEY_ERROR_MESSAGE)
                    ?: "Sync failed"
                _operationState.value = OperationState.Error(error)
                currentWorkId = null
                workManager.pruneWork()
            }
            else -> {}
        }
    }

    fun resetOperationState() {
        _operationState.value = OperationState.Idle
    }

    fun dismissError() {
        if (_uiState.value is GoogleDriveUiState.Error) {
            checkSignInStatus()
        }
        if (_operationState.value is OperationState.Error) {
            _operationState.value = OperationState.Idle
        }
    }
}

sealed class GoogleDriveUiState {
    object Loading : GoogleDriveUiState()
    object SignedOut : GoogleDriveUiState()
    data class SignedIn(val email: String) : GoogleDriveUiState()
    data class Error(val message: String) : GoogleDriveUiState()
}

sealed class OperationState {
    object Idle : OperationState()
    object Syncing : OperationState()
    data class SyncComplete(val uploaded: Int, val downloaded: Int, val updated: Int, val deleted: Int) : OperationState()
    data class Error(val message: String) : OperationState()
}
