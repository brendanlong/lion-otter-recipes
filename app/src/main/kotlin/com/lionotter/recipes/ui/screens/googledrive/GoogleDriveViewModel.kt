package com.lionotter.recipes.ui.screens.googledrive

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.lionotter.recipes.data.remote.DriveFolder
import com.lionotter.recipes.data.remote.GoogleDriveService
import com.lionotter.recipes.data.sync.DriveSyncManager
import com.lionotter.recipes.worker.DriveSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GoogleDriveViewModel @Inject constructor(
    private val googleDriveService: GoogleDriveService,
    private val syncManager: DriveSyncManager,
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

    /**
     * Combined sync status showing current sync configuration and state.
     */
    val syncStatus: StateFlow<SyncStatusState> = combine(
        syncManager.syncState,
        syncManager.pendingOperationCount,
        syncManager.conflictCount
    ) { syncState, pendingCount, conflictCount ->
        SyncStatusState(
            isEnabled = syncState?.syncEnabled == true,
            syncFolderName = syncState?.syncFolderName,
            syncFolderId = syncState?.syncFolderId,
            pendingOperations = pendingCount,
            conflicts = conflictCount,
            lastSyncAt = syncState?.lastFullSyncAt,
            lastError = syncState?.lastSyncError
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SyncStatusState()
    )

    init {
        checkSignInStatus()
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
            // Disable sync when signing out
            syncManager.disableSync()
            DriveSyncWorker.cancelPeriodicSync(workManager)

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

    // ========== Sync Configuration ==========

    /**
     * Enable sync with the specified folder.
     * All recipes will be continuously synced to this folder.
     */
    fun enableSync(folder: DriveFolder) {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        viewModelScope.launch {
            syncManager.enableSync(folder.id, folder.name)

            // Schedule periodic sync and trigger immediate sync
            DriveSyncWorker.schedulePeriodicSync(workManager)
            DriveSyncWorker.triggerSync(workManager)

            _operationState.value = OperationState.SyncEnabled(folder.name)
        }
    }

    /**
     * Disable sync.
     */
    fun disableSync() {
        viewModelScope.launch {
            syncManager.disableSync()
            DriveSyncWorker.cancelPeriodicSync(workManager)
            _operationState.value = OperationState.SyncDisabled
        }
    }

    /**
     * Trigger a manual sync.
     */
    fun triggerManualSync() {
        if (_uiState.value !is GoogleDriveUiState.SignedIn) {
            _operationState.value = OperationState.Error("Please sign in to Google Drive first")
            return
        }

        DriveSyncWorker.triggerSync(workManager)
        _operationState.value = OperationState.Syncing
    }

    /**
     * Get conflicts for resolution.
     */
    fun getConflicts() {
        viewModelScope.launch {
            val conflicts = syncManager.getConflicts()
            if (conflicts.isNotEmpty()) {
                _operationState.value = OperationState.HasConflicts(conflicts.size)
            }
        }
    }

    /**
     * Resolve a conflict by keeping the local version.
     */
    fun resolveConflictKeepLocal(recipeId: String) {
        viewModelScope.launch {
            syncManager.resolveConflictKeepLocal(recipeId)
            DriveSyncWorker.triggerSync(workManager)
        }
    }

    /**
     * Resolve a conflict by keeping the remote version.
     */
    fun resolveConflictKeepRemote(recipeId: String) {
        viewModelScope.launch {
            syncManager.resolveConflictKeepRemote(recipeId)
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
    data class SyncEnabled(val folderName: String) : OperationState()
    object SyncDisabled : OperationState()
    data class HasConflicts(val count: Int) : OperationState()
    data class Error(val message: String) : OperationState()
}

/**
 * Current sync status for display in the UI.
 */
data class SyncStatusState(
    val isEnabled: Boolean = false,
    val syncFolderName: String? = null,
    val syncFolderId: String? = null,
    val pendingOperations: Int = 0,
    val conflicts: Int = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null
)
