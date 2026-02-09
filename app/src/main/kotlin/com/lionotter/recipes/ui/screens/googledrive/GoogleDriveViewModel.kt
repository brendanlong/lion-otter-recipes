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
import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.worker.GoogleDriveExportWorker
import com.lionotter.recipes.worker.GoogleDriveImportWorker
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
    }

    private val _uiState = MutableStateFlow<GoogleDriveUiState>(GoogleDriveUiState.Loading)
    val uiState: StateFlow<GoogleDriveUiState> = _uiState.asStateFlow()

    private val _folders = MutableStateFlow<List<DriveFolder>>(emptyList())
    val folders: StateFlow<List<DriveFolder>> = _folders.asStateFlow()

    private val _folderNavigationStack = MutableStateFlow<List<DriveFolder>>(emptyList())
    val folderNavigationStack: StateFlow<List<DriveFolder>> = _folderNavigationStack.asStateFlow()

    private val _isLoadingFolders = MutableStateFlow(false)
    val isLoadingFolders: StateFlow<Boolean> = _isLoadingFolders.asStateFlow()

    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    val syncEnabled: StateFlow<Boolean> = settingsDataStore.googleDriveSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val syncFolderName: StateFlow<String?> = settingsDataStore.googleDriveSyncFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastSyncTimestamp: StateFlow<String?> = settingsDataStore.googleDriveLastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
                loadRootFolders()
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
                loadRootFolders()
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
            _folders.value = emptyList()
        }
    }

    private fun loadRootFolders() {
        viewModelScope.launch {
            val result = googleDriveService.listFolders()
            result.onSuccess { folders ->
                _folders.value = folders
            }.onFailure { error ->
                _uiState.value = GoogleDriveUiState.Error("Failed to load folders: ${error.message}")
            }
        }
    }

    fun loadFolders(parentFolderId: String? = null) {
        viewModelScope.launch {
            _isLoadingFolders.value = true
            val result = googleDriveService.listFolders(parentFolderId)
            result.onSuccess { folders ->
                _folders.value = folders
            }.onFailure { error ->
                _operationState.value = OperationState.Error("Failed to load folders: ${error.message}")
            }
            _isLoadingFolders.value = false
        }
    }

    /**
     * Navigate into a folder. Adds the folder to the navigation stack and loads its contents.
     */
    fun navigateToFolder(folder: DriveFolder) {
        _folderNavigationStack.value = _folderNavigationStack.value + folder
        loadFolders(folder.id)
    }

    /**
     * Navigate back to the parent folder.
     */
    fun navigateBack() {
        val stack = _folderNavigationStack.value
        if (stack.isNotEmpty()) {
            _folderNavigationStack.value = stack.dropLast(1)
            val parentId = _folderNavigationStack.value.lastOrNull()?.id
            loadFolders(parentId)
        }
    }

    /**
     * Reset folder navigation to root.
     */
    fun resetFolderNavigation() {
        _folderNavigationStack.value = emptyList()
        loadFolders(null)
    }

    /**
     * Get the current folder ID (null if at root).
     */
    fun getCurrentFolderId(): String? = _folderNavigationStack.value.lastOrNull()?.id

    fun exportToGoogleDrive(parentFolderId: String? = null) {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<GoogleDriveExportWorker>()
            .setInputData(GoogleDriveExportWorker.createInputData(parentFolderId))
            .addTag(GoogleDriveExportWorker.TAG_DRIVE_EXPORT)
            .build()

        currentWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _operationState.value = OperationState.Exporting
    }

    fun importFromGoogleDrive(folderId: String) {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<GoogleDriveImportWorker>()
            .setInputData(GoogleDriveImportWorker.createInputData(folderId))
            .addTag(GoogleDriveImportWorker.TAG_DRIVE_IMPORT)
            .build()

        currentWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _operationState.value = OperationState.Importing
    }

    /**
     * Enable sync with a selected folder and schedule periodic background sync.
     * If folderId is null, a default folder will be created on first sync.
     */
    fun enableSync(folderId: String?, folderName: String?) {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        viewModelScope.launch {
            if (folderId != null && folderName != null) {
                settingsDataStore.setGoogleDriveSyncFolder(folderId, folderName)
            }
            settingsDataStore.setGoogleDriveSyncEnabled(true)
            schedulePeriodicSync()
            // Trigger an immediate sync
            triggerSync()
        }
    }

    /**
     * Change the sync folder and trigger a re-sync.
     */
    fun changeSyncFolder(folderId: String?, folderName: String?) {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        viewModelScope.launch {
            if (folderId != null && folderName != null) {
                settingsDataStore.setGoogleDriveSyncFolder(folderId, folderName)
            } else {
                // Clear folder ID/name so sync will create a new default folder
                settingsDataStore.clearGoogleDriveSyncFolderOnly()
            }
            // Trigger an immediate sync with the new folder
            triggerSync()
        }
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
            workManager.observeWorkByTag(GoogleDriveExportWorker.TAG_DRIVE_EXPORT) { currentWorkId }
                .collect { handleExportWorkInfo(it) }
        }
        viewModelScope.launch {
            workManager.observeWorkByTag(GoogleDriveImportWorker.TAG_DRIVE_IMPORT) { currentWorkId }
                .collect { handleImportWorkInfo(it) }
        }
        viewModelScope.launch {
            workManager.observeWorkByTag(GoogleDriveSyncWorker.TAG_DRIVE_SYNC) { currentWorkId }
                .collect { handleSyncWorkInfo(it) }
        }
    }

    private fun handleExportWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                val current = workInfo.progress.getInt(GoogleDriveExportWorker.KEY_CURRENT, 0)
                val total = workInfo.progress.getInt(GoogleDriveExportWorker.KEY_TOTAL, 0)
                val recipeName = workInfo.progress.getString(GoogleDriveExportWorker.KEY_RECIPE_NAME)
                _operationState.value = OperationState.Exporting
            }
            WorkInfo.State.SUCCEEDED -> {
                val exported = workInfo.outputData.getInt(GoogleDriveExportWorker.KEY_EXPORTED_COUNT, 0)
                val failed = workInfo.outputData.getInt(GoogleDriveExportWorker.KEY_FAILED_COUNT, 0)
                _operationState.value = OperationState.ExportComplete(exported, failed)
                currentWorkId = null
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(GoogleDriveExportWorker.KEY_ERROR_MESSAGE)
                    ?: "Export failed"
                _operationState.value = OperationState.Error(error)
                currentWorkId = null
                workManager.pruneWork()
            }
            else -> {}
        }
    }

    private fun handleImportWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                val current = workInfo.progress.getInt(GoogleDriveImportWorker.KEY_CURRENT, 0)
                val total = workInfo.progress.getInt(GoogleDriveImportWorker.KEY_TOTAL, 0)
                _operationState.value = OperationState.Importing
            }
            WorkInfo.State.SUCCEEDED -> {
                val imported = workInfo.outputData.getInt(GoogleDriveImportWorker.KEY_IMPORTED_COUNT, 0)
                val failed = workInfo.outputData.getInt(GoogleDriveImportWorker.KEY_FAILED_COUNT, 0)
                val skipped = workInfo.outputData.getInt(GoogleDriveImportWorker.KEY_SKIPPED_COUNT, 0)
                _operationState.value = OperationState.ImportComplete(imported, failed, skipped)
                currentWorkId = null
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(GoogleDriveImportWorker.KEY_ERROR_MESSAGE)
                    ?: "Import failed"
                _operationState.value = OperationState.Error(error)
                currentWorkId = null
                workManager.pruneWork()
            }
            else -> {}
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
    object Exporting : OperationState()
    object Importing : OperationState()
    object Syncing : OperationState()
    data class ExportComplete(val exportedCount: Int, val failedCount: Int) : OperationState()
    data class ImportComplete(val importedCount: Int, val failedCount: Int, val skippedCount: Int) : OperationState()
    data class SyncComplete(val uploaded: Int, val downloaded: Int, val updated: Int, val deleted: Int) : OperationState()
    data class Error(val message: String) : OperationState()
}
