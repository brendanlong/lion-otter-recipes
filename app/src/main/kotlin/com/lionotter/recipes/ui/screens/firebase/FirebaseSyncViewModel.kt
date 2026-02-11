package com.lionotter.recipes.ui.screens.firebase

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FirebaseSyncViewModel @Inject constructor(
    private val firestoreService: FirestoreService,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow<FirebaseSyncUiState>(FirebaseSyncUiState.Loading)
    val uiState: StateFlow<FirebaseSyncUiState> = _uiState.asStateFlow()

    val syncEnabled: StateFlow<Boolean> = settingsDataStore.firebaseSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            // Check current auth state (ensureSignedIn is called at app startup)
            val signedIn = firestoreService.isSignedInWithGoogle()
            _uiState.value = if (signedIn) {
                FirebaseSyncUiState.SignedIn
            } else {
                FirebaseSyncUiState.SignedOut
            }
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

                if (success) {
                    _uiState.value = FirebaseSyncUiState.SignedIn
                    // Auto-enable sync when signing in with Google
                    settingsDataStore.setFirebaseSyncEnabled(true)
                    firestoreService.enableNetwork()
                } else {
                    _uiState.value = FirebaseSyncUiState.Error("Sign in failed - could not authenticate")
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
            settingsDataStore.setFirebaseSyncEnabled(false)
            firestoreService.signOut()
            _uiState.value = FirebaseSyncUiState.SignedOut
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Only allow enabling sync when signed in with Google
            if (enabled && !firestoreService.isSignedInWithGoogle()) return@launch
            settingsDataStore.setFirebaseSyncEnabled(enabled)
            if (enabled) {
                firestoreService.enableNetwork()
            } else {
                firestoreService.disableNetwork()
            }
        }
    }

    fun dismissError() {
        if (_uiState.value is FirebaseSyncUiState.Error) {
            _uiState.value = if (firestoreService.isSignedInWithGoogle()) {
                FirebaseSyncUiState.SignedIn
            } else {
                FirebaseSyncUiState.SignedOut
            }
        }
    }
}

sealed class FirebaseSyncUiState {
    object Loading : FirebaseSyncUiState()
    object SignedOut : FirebaseSyncUiState()
    object SignedIn : FirebaseSyncUiState()
    data class Error(val message: String) : FirebaseSyncUiState()
}
