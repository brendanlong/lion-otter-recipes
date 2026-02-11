package com.lionotter.recipes.ui.screens.firebase

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.worker.FirestoreSyncWorker
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
class FirebaseSyncViewModel @Inject constructor(
    private val firestoreService: FirestoreService,
    private val workManager: WorkManager,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    companion object {
        const val PERIODIC_SYNC_WORK_NAME = "firebase_periodic_sync"
    }

    private val _uiState = MutableStateFlow<FirebaseSyncUiState>(FirebaseSyncUiState.Loading)
    val uiState: StateFlow<FirebaseSyncUiState> = _uiState.asStateFlow()

    private val _operationState = MutableStateFlow<SyncOperationState>(SyncOperationState.Idle)
    val operationState: StateFlow<SyncOperationState> = _operationState.asStateFlow()

    val syncEnabled: StateFlow<Boolean> = settingsDataStore.firebaseSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val lastSyncTimestamp: StateFlow<String?> = settingsDataStore.firebaseLastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var currentWorkId: UUID? = null

    init {
        checkSignInStatus()
        observeWorkStatus()
    }

    private fun checkSignInStatus() {
        _uiState.value = if (firestoreService.isSignedIn()) {
            FirebaseSyncUiState.SignedIn
        } else {
            FirebaseSyncUiState.SignedOut
        }
    }

    /**
     * Initiate the sign-in flow using Credential Manager.
     * First tries with filterByAuthorizedAccounts=true (shows only previously used accounts).
     * If no credentials found, retries with filterByAuthorizedAccounts=false (shows all accounts).
     */
    fun signIn() {
        viewModelScope.launch {
            _uiState.value = FirebaseSyncUiState.Loading
            try {
                val success = try {
                    firestoreService.signInWithGoogle(filterByAuthorizedAccounts = true)
                } catch (e: NoCredentialException) {
                    // No previously authorized accounts, try showing all accounts
                    firestoreService.signInWithGoogle(filterByAuthorizedAccounts = false)
                }

                _uiState.value = if (success) {
                    FirebaseSyncUiState.SignedIn
                } else {
                    FirebaseSyncUiState.Error("Sign in failed - could not authenticate")
                }
            } catch (e: GetCredentialCancellationException) {
                // User cancelled the sign-in flow
                _uiState.value = FirebaseSyncUiState.SignedOut
            } catch (e: NoCredentialException) {
                _uiState.value = FirebaseSyncUiState.Error(
                    "No Google accounts available. Please add a Google account to your device."
                )
            } catch (e: Exception) {
                _uiState.value = FirebaseSyncUiState.Error("Sign in failed: ${e.message}")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            disableSync()
            firestoreService.signOut()
            _uiState.value = FirebaseSyncUiState.SignedOut
        }
    }

    /**
     * Enable sync and trigger initial sync.
     */
    fun enableSync() {
        if (_uiState.value !is FirebaseSyncUiState.SignedIn) {
            _operationState.value = SyncOperationState.Error("Please sign in first")
            return
        }

        viewModelScope.launch {
            settingsDataStore.setFirebaseSyncEnabled(true)
            schedulePeriodicSync()
            triggerSync()
        }
    }

    /**
     * Disable sync and cancel periodic work.
     */
    fun disableSync() {
        viewModelScope.launch {
            settingsDataStore.setFirebaseSyncEnabled(false)
            workManager.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
        }
    }

    /**
     * Trigger a one-time sync immediately.
     */
    fun triggerSync() {
        if (_uiState.value !is FirebaseSyncUiState.SignedIn) {
            _operationState.value = SyncOperationState.Error("Please sign in first")
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<FirestoreSyncWorker>()
            .setInputData(FirestoreSyncWorker.createInputData())
            .addTag(FirestoreSyncWorker.TAG_FIRESTORE_SYNC)
            .build()

        currentWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _operationState.value = SyncOperationState.Syncing
    }

    /**
     * Schedule periodic sync every 6 hours.
     */
    private fun schedulePeriodicSync() {
        val periodicWorkRequest = PeriodicWorkRequestBuilder<FirestoreSyncWorker>(
            6, TimeUnit.HOURS
        )
            .addTag(FirestoreSyncWorker.TAG_FIRESTORE_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )
    }

    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(FirestoreSyncWorker.TAG_FIRESTORE_SYNC) { currentWorkId }
                .collect { handleSyncWorkInfo(it) }
        }
    }

    private fun handleSyncWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                _operationState.value = SyncOperationState.Syncing
            }
            WorkInfo.State.SUCCEEDED -> {
                val resultType = workInfo.outputData.getString(FirestoreSyncWorker.KEY_RESULT_TYPE)
                if (resultType == FirestoreSyncWorker.RESULT_SUCCESS) {
                    val uploaded = workInfo.outputData.getInt(FirestoreSyncWorker.KEY_UPLOADED_COUNT, 0)
                    val downloaded = workInfo.outputData.getInt(FirestoreSyncWorker.KEY_DOWNLOADED_COUNT, 0)
                    val updated = workInfo.outputData.getInt(FirestoreSyncWorker.KEY_UPDATED_COUNT, 0)
                    val deleted = workInfo.outputData.getInt(FirestoreSyncWorker.KEY_DELETED_COUNT, 0)
                    _operationState.value = SyncOperationState.SyncComplete(uploaded, downloaded, updated, deleted)
                } else {
                    _operationState.value = SyncOperationState.Idle
                }
                currentWorkId = null
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(FirestoreSyncWorker.KEY_ERROR_MESSAGE)
                    ?: "Sync failed"
                _operationState.value = SyncOperationState.Error(error)
                currentWorkId = null
                workManager.pruneWork()
            }
            else -> {}
        }
    }

    fun resetOperationState() {
        _operationState.value = SyncOperationState.Idle
    }

    fun dismissError() {
        if (_uiState.value is FirebaseSyncUiState.Error) {
            checkSignInStatus()
        }
        if (_operationState.value is SyncOperationState.Error) {
            _operationState.value = SyncOperationState.Idle
        }
    }
}

sealed class FirebaseSyncUiState {
    object Loading : FirebaseSyncUiState()
    object SignedOut : FirebaseSyncUiState()
    object SignedIn : FirebaseSyncUiState()
    data class Error(val message: String) : FirebaseSyncUiState()
}

sealed class SyncOperationState {
    object Idle : SyncOperationState()
    object Syncing : SyncOperationState()
    data class SyncComplete(val uploaded: Int, val downloaded: Int, val updated: Int, val deleted: Int) : SyncOperationState()
    data class Error(val message: String) : SyncOperationState()
}
