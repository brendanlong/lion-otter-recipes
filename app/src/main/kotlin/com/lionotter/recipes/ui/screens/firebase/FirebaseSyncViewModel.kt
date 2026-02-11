package com.lionotter.recipes.ui.screens.firebase

import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.sync.RealtimeSyncManager
import com.lionotter.recipes.data.sync.SyncConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class FirebaseSyncUiState {
    object Loading : FirebaseSyncUiState()
    object SignedOut : FirebaseSyncUiState()
    data class SignedIn(
        val userEmail: String? = null
    ) : FirebaseSyncUiState()
    data class Error(val message: String) : FirebaseSyncUiState()
}

@HiltViewModel
class FirebaseSyncViewModel @Inject constructor(
    private val firestoreService: FirestoreService,
    private val settingsDataStore: SettingsDataStore,
    private val realtimeSyncManager: RealtimeSyncManager
) : ViewModel() {

    companion object {
        private const val TAG = "FirebaseSyncViewModel"
    }

    private val _uiState = MutableStateFlow<FirebaseSyncUiState>(FirebaseSyncUiState.Loading)
    val uiState: StateFlow<FirebaseSyncUiState> = _uiState.asStateFlow()

    val syncEnabled: StateFlow<Boolean> = settingsDataStore.firebaseSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectionState: StateFlow<SyncConnectionState> = realtimeSyncManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncConnectionState.DISCONNECTED)

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var signInInProgress = false

    init {
        checkSignInStatus()

        // Forward sync errors from RealtimeSyncManager to our error message
        viewModelScope.launch {
            realtimeSyncManager.syncError.collect { error ->
                if (error != null) {
                    _errorMessage.value = error
                    realtimeSyncManager.clearSyncError()
                }
            }
        }
    }

    private fun checkSignInStatus() {
        _uiState.value = if (firestoreService.isSignedIn()) {
            FirebaseSyncUiState.SignedIn()
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
        if (signInInProgress) return
        signInInProgress = true

        viewModelScope.launch {
            _uiState.value = FirebaseSyncUiState.Loading
            try {
                val success = try {
                    firestoreService.signInWithGoogle(filterByAuthorizedAccounts = true)
                } catch (_: NoCredentialException) {
                    firestoreService.signInWithGoogle(filterByAuthorizedAccounts = false)
                }

                if (success) {
                    _uiState.value = FirebaseSyncUiState.SignedIn()
                } else {
                    _uiState.value = FirebaseSyncUiState.SignedOut
                    _errorMessage.value = "Sign in failed"
                }
            } catch (e: GetCredentialCancellationException) {
                _uiState.value = FirebaseSyncUiState.SignedOut
                _errorMessage.value = "Sign in cancelled"
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Sign in failed", e)
                _uiState.value = FirebaseSyncUiState.SignedOut
                _errorMessage.value = handleCredentialError(e)
            } catch (e: Exception) {
                Log.e(TAG, "Sign in failed", e)
                _uiState.value = FirebaseSyncUiState.SignedOut
                _errorMessage.value = "Sign in failed: ${e.message}"
            } finally {
                signInInProgress = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            realtimeSyncManager.stopSync()
            firestoreService.signOut()
            settingsDataStore.clearFirebaseSyncSettings()
            _uiState.value = FirebaseSyncUiState.SignedOut
        }
    }

    /**
     * Enable sync. LionOtterApp observes this setting and starts RealtimeSyncManager.
     */
    fun enableSync() {
        viewModelScope.launch {
            settingsDataStore.setFirebaseSyncEnabled(true)
        }
    }

    /**
     * Disable sync. LionOtterApp observes this setting and stops RealtimeSyncManager.
     */
    fun disableSync() {
        viewModelScope.launch {
            settingsDataStore.setFirebaseSyncEnabled(false)
        }
    }

    fun dismissError() {
        _errorMessage.value = null
        if (_uiState.value is FirebaseSyncUiState.Error) {
            checkSignInStatus()
        }
    }

    private fun handleCredentialError(e: GetCredentialException): String {
        return when {
            e.message?.contains("16:") == true -> "Network error. Please check your internet connection."
            e.message?.contains("10:") == true ->
                "App not configured. Please set up OAuth in Google Cloud Console with your app's SHA-1 fingerprint."
            else -> "Sign in failed (${e.type})"
        }
    }
}
