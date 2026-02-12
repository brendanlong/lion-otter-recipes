package com.lionotter.recipes.ui.screens.firebase

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Firebase sync section in Settings.
 */
sealed class FirebaseSyncUiState {
    object Loading : FirebaseSyncUiState()
    object SignedOut : FirebaseSyncUiState()
    data class SignedIn(val displayName: String?) : FirebaseSyncUiState()
    data class Error(val message: String) : FirebaseSyncUiState()
}

@HiltViewModel
class FirebaseSyncViewModel @Inject constructor(
    private val firestoreService: FirestoreService,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FirebaseSyncUiState>(FirebaseSyncUiState.Loading)
    val uiState: StateFlow<FirebaseSyncUiState> = _uiState.asStateFlow()

    /**
     * Whether sync (network access) is enabled. Only meaningful when signed in with Google.
     */
    val syncEnabled: StateFlow<Boolean> = settingsDataStore.firebaseSyncEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /**
     * Whether the user is signed in with Google (not anonymous).
     */
    val isGoogleSignedIn: StateFlow<Boolean> = firestoreService.isGoogleSignedIn

    init {
        checkSignInStatus()
    }

    private fun checkSignInStatus() {
        _uiState.value = if (firestoreService.isSignedIn()) {
            FirebaseSyncUiState.SignedIn(displayName = null)
        } else {
            FirebaseSyncUiState.SignedOut
        }
    }

    fun signIn() {
        // Use appScope so sign-in completes even if user navigates away
        appScope.launch {
            _uiState.value = FirebaseSyncUiState.Loading
            try {
                val success = try {
                    firestoreService.signInWithGoogle(filterByAuthorizedAccounts = true)
                } catch (e: NoCredentialException) {
                    firestoreService.signInWithGoogle(filterByAuthorizedAccounts = false)
                }

                if (success) {
                    _uiState.value = FirebaseSyncUiState.SignedIn(displayName = null)
                    // Enable sync by default when signing in
                    settingsDataStore.setFirebaseSyncEnabled(true)
                } else {
                    _uiState.value = FirebaseSyncUiState.SignedOut
                }
            } catch (e: GetCredentialCancellationException) {
                _uiState.value = FirebaseSyncUiState.SignedOut
            } catch (e: NoCredentialException) {
                _uiState.value = FirebaseSyncUiState.Error("No Google account found on this device")
            } catch (e: Exception) {
                _uiState.value = FirebaseSyncUiState.Error(e.message ?: "Sign-in failed")
            }
        }
    }

    fun signOut() {
        // Use appScope so sign-out completes even if user navigates away
        appScope.launch {
            try {
                settingsDataStore.setFirebaseSyncEnabled(false)
                firestoreService.signOut()
                _uiState.value = FirebaseSyncUiState.SignedOut
            } catch (e: Exception) {
                _uiState.value = FirebaseSyncUiState.Error(e.message ?: "Sign-out failed")
            }
        }
    }

    /**
     * Enable sync (network access) for signed-in Google users.
     */
    fun enableSync() {
        // Use appScope so network toggle completes even if user navigates away
        appScope.launch {
            settingsDataStore.setFirebaseSyncEnabled(true)
            firestoreService.enableNetwork()
        }
    }

    /**
     * Disable sync (network access). Data remains available from local cache.
     */
    fun disableSync() {
        // Use appScope so network toggle completes even if user navigates away
        appScope.launch {
            settingsDataStore.setFirebaseSyncEnabled(false)
            firestoreService.disableNetwork()
        }
    }

    fun dismissError() {
        checkSignInStatus()
    }
}
