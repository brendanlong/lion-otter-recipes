package com.lionotter.recipes.ui.screens.googledrive

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.worker.GoogleDriveExportWorker
import com.lionotter.recipes.worker.GoogleDriveImportWorker
import com.lionotter.recipes.worker.observeWorkByTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GoogleDriveViewModel @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val workManager: WorkManager
) : ViewModel() {

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

    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(GoogleDriveExportWorker.TAG_DRIVE_EXPORT) { currentWorkId }
                .collect { handleExportWorkInfo(it) }
        }
        viewModelScope.launch {
            workManager.observeWorkByTag(GoogleDriveImportWorker.TAG_DRIVE_IMPORT) { currentWorkId }
                .collect { handleImportWorkInfo(it) }
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
    data class ExportComplete(val exportedCount: Int, val failedCount: Int) : OperationState()
    data class ImportComplete(val importedCount: Int, val failedCount: Int, val skippedCount: Int) : OperationState()
    data class Error(val message: String) : OperationState()
}
