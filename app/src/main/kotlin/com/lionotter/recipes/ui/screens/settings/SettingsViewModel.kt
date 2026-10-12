package com.lionotter.recipes.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.AuthCredential
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.AccountMigrationService
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.ReauthenticationRequiredException
import com.lionotter.recipes.data.remote.SignOutResult
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.worker.LocalImageUploadWorker
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val importDebugRepository: ImportDebugRepository,
    private val authService: AuthService,
    private val accountMigrationService: AccountMigrationService,
    private val workManager: WorkManager
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val authState: StateFlow<AuthState> = authService.authState

    val apiKey: StateFlow<String?> = settingsDataStore.anthropicApiKey
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val aiModel: StateFlow<String> = settingsDataStore.aiModel
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AnthropicService.DEFAULT_MODEL
        )

    val editModel: StateFlow<String> = settingsDataStore.editModel
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AnthropicService.DEFAULT_EDIT_MODEL
        )

    val thinkingEnabled: StateFlow<Boolean> = settingsDataStore.thinkingEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsDataStore.DEFAULT_THINKING_ENABLED
        )

    val keepScreenOn: StateFlow<Boolean> = settingsDataStore.keepScreenOn
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val themeMode: StateFlow<ThemeMode> = settingsDataStore.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.AUTO
        )

    val volumeUnitSystem: StateFlow<UnitSystem> = settingsDataStore.volumeUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val weightUnitSystem: StateFlow<UnitSystem> = settingsDataStore.weightUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val groceryVolumeUnitSystem: StateFlow<UnitSystem> = settingsDataStore.groceryVolumeUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val groceryWeightUnitSystem: StateFlow<UnitSystem> = settingsDataStore.groceryWeightUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val startOfWeek: StateFlow<StartOfWeek> = settingsDataStore.startOfWeek
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StartOfWeek.LOCALE_DEFAULT
        )

    val importDebuggingEnabled: StateFlow<Boolean> = settingsDataStore.importDebuggingEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _apiKeyInput = MutableStateFlow("")
    val apiKeyInput: StateFlow<String> = _apiKeyInput.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount.asStateFlow()

    private val _toastMessage = MutableSharedFlow<Int>(extraBufferCapacity = 10)
    val toastMessage: SharedFlow<Int> = _toastMessage.asSharedFlow()

    fun onApiKeyInputChange(input: String) {
        _apiKeyInput.value = input
    }

    fun saveApiKey() {
        val key = _apiKeyInput.value.trim()

        val validationError = AnthropicService.validateApiKey(key)
        if (validationError != null) {
            _saveState.value = SaveState.Error(validationError)
            return
        }

        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    settingsDataStore.setAnthropicApiKey(key)
                }
                _apiKeyInput.value = ""
                _saveState.value = SaveState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save API key", e)
                _saveState.value = SaveState.Error("Failed to save: ${e.message}")
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            if (writeSetting("clear API key") { settingsDataStore.clearAnthropicApiKey() }) {
                _saveState.value = SaveState.Idle
            }
        }
    }

    /**
     * Runs a settings write that survives viewModelScope cancellation. On failure, logs and
     * shows a toast so the user knows the change didn't stick.
     *
     * @return true if the write succeeded
     */
    private suspend fun writeSetting(description: String, write: suspend () -> Unit): Boolean {
        return try {
            withContext(NonCancellable) { write() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to $description", e)
            _toastMessage.tryEmit(R.string.settings_save_failed)
            false
        }
    }

    private fun launchSettingWrite(description: String, write: suspend () -> Unit) {
        viewModelScope.launch { writeSetting(description, write) }
    }

    fun setAiModel(model: String) =
        launchSettingWrite("set AI model") { settingsDataStore.setAiModel(model) }

    fun setEditModel(model: String) =
        launchSettingWrite("set edit model") { settingsDataStore.setEditModel(model) }

    fun setThinkingEnabled(enabled: Boolean) =
        launchSettingWrite("set thinking mode") { settingsDataStore.setThinkingEnabled(enabled) }

    fun setKeepScreenOn(enabled: Boolean) =
        launchSettingWrite("set keep screen on") { settingsDataStore.setKeepScreenOn(enabled) }

    fun setThemeMode(mode: ThemeMode) =
        launchSettingWrite("set theme") { settingsDataStore.setThemeMode(mode) }

    fun setVolumeUnitSystem(system: UnitSystem) =
        launchSettingWrite("set volume units") { settingsDataStore.setVolumeUnitSystem(system) }

    fun setWeightUnitSystem(system: UnitSystem) =
        launchSettingWrite("set weight units") { settingsDataStore.setWeightUnitSystem(system) }

    fun setGroceryVolumeUnitSystem(system: UnitSystem) =
        launchSettingWrite("set grocery volume units") {
            settingsDataStore.setGroceryVolumeUnitSystem(system)
        }

    fun setGroceryWeightUnitSystem(system: UnitSystem) =
        launchSettingWrite("set grocery weight units") {
            settingsDataStore.setGroceryWeightUnitSystem(system)
        }

    fun setStartOfWeek(startOfWeek: StartOfWeek) =
        launchSettingWrite("set start of week") { settingsDataStore.setStartOfWeek(startOfWeek) }

    /**
     * Enables or disables import debugging. Disabling also deletes all stored debug entries,
     * so the UI confirms with the user before calling this with `false`.
     */
    fun setImportDebuggingEnabled(enabled: Boolean) =
        launchSettingWrite("set import debugging") {
            settingsDataStore.setImportDebuggingEnabled(enabled)
            if (!enabled) {
                importDebugRepository.deleteAllDebugEntries()
            }
        }

    private val _showUnsyncedSignOutWarning = MutableStateFlow(false)
    val showUnsyncedSignOutWarning: StateFlow<Boolean> = _showUnsyncedSignOutWarning.asStateFlow()

    /**
     * Signs out unless local changes haven't synced yet, in which case the UI is asked
     * to confirm via [showUnsyncedSignOutWarning] before [signOut] is called with [force].
     */
    fun signOut(force: Boolean = false) {
        _showUnsyncedSignOutWarning.value = false
        launchSettingWrite("sign out") {
            if (authService.signOut(force) is SignOutResult.PendingWrites) {
                _showUnsyncedSignOutWarning.value = true
            }
        }
    }

    fun dismissUnsyncedSignOutWarning() {
        _showUnsyncedSignOutWarning.value = false
    }

    fun onDeleteAccountStarted() {
        _isDeletingAccount.value = true
    }

    /**
     * Requests a fresh Google credential so the account can be deleted. Must be called
     * from a UI-owned coroutine scope. Returns null if the user cancelled.
     */
    suspend fun requestReauthCredential(activityContext: Context): Result<AuthCredential>? = try {
        Result.success(authService.requestReauthCredential(activityContext))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ReauthenticationRequiredException) {
        if (e.cause is GetCredentialCancellationException) null else Result.failure(e)
    }

    /**
     * Deletes the user's account and all associated data (recipes, meal plans,
     * images) from Firebase, then transitions to guest mode.
     * A null [reauthResult] means the user cancelled re-authentication.
     */
    fun deleteAccount(reauthResult: Result<AuthCredential>?) {
        if (reauthResult == null) {
            _isDeletingAccount.value = false
            return
        }
        viewModelScope.launch {
            _isDeletingAccount.value = true
            try {
                withContext(NonCancellable) {
                    authService.deleteAccountAndData(reauthResult.getOrNull())
                }
                _toastMessage.tryEmit(R.string.delete_account_success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete account", e)
                _toastMessage.tryEmit(
                    when (e) {
                        is ReauthenticationRequiredException -> R.string.delete_account_reauth_required
                        is AccountDeletionService.OfflineException -> R.string.delete_account_offline
                        is AccountDeletionService.AuthAccountDeletionException ->
                            R.string.delete_account_auth_failed
                        else -> R.string.delete_account_failed
                    }
                )
            } finally {
                _isDeletingAccount.value = false
            }
        }
    }

    /**
     * Called by the UI right before it starts the Credential Manager flow.
     * The credential request runs in the UI's coroutine scope (see [requestGoogleCredential])
     * so the Activity context is never captured by a ViewModel coroutine.
     */
    fun onGoogleSignInStarted() {
        _isSigningIn.value = true
    }

    /** Called when the UI's credential request was cancelled (e.g. the screen left composition). */
    fun onGoogleSignInAborted() {
        _isSigningIn.value = false
    }

    /**
     * Requests a Google credential via Credential Manager. Must be called from a UI-owned
     * coroutine scope, since [activityContext] must not outlive the Activity.
     */
    suspend fun requestGoogleCredential(activityContext: Context): Result<AuthCredential> =
        authService.signInWithGoogle(activityContext)

    /**
     * Completes Google sign-in with the credential obtained by [requestGoogleCredential]:
     * migrates guest data to the Google account, then finishes the sign-in.
     */
    fun onGoogleCredentialResult(credentialResult: Result<AuthCredential>) {
        viewModelScope.launch {
            _isSigningIn.value = true
            try {
                credentialResult.fold(
                    onSuccess = { credential ->
                        performMigration(credential)
                    },
                    onFailure = { e ->
                        if (e !is GetCredentialCancellationException) {
                            Log.e(TAG, "Failed to get Google credential", e)
                            _toastMessage.tryEmit(R.string.sign_in_failed)
                        }
                    }
                )
            } finally {
                _isSigningIn.value = false
            }
        }
    }

    /**
     * Migrates guest data to the Google account, then completes the sign-in.
     */
    private suspend fun performMigration(credential: AuthCredential) {
        var signedIn = false
        try {
            withContext(NonCancellable) {
                val guestUid = authService.getGuestUid()
                    ?: run {
                        Log.e(TAG, "No guest UID found for migration")
                        _toastMessage.tryEmit(R.string.sign_in_failed)
                        return@withContext
                    }
                val result = accountMigrationService.migrateGuestData(credential, guestUid)
                if (result.isFailure) {
                    Log.e(TAG, "Migration failed", result.exceptionOrNull())
                    authService.restoreAfterFailedMerge()
                    _toastMessage.tryEmit(R.string.sign_in_failed)
                    return@withContext
                }
                authService.completeMergeSignIn()
                signedIn = true
                _toastMessage.tryEmit(R.string.signed_in_with_google)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during account migration", e)
            authService.restoreAfterFailedMerge()
            _toastMessage.tryEmit(R.string.sign_in_migration_warning)
        }

        if (signedIn) {
            uploadLocalImages()
        }
    }

    /** Uploads images picked as a guest (still file://) to the newly linked account. */
    private fun uploadLocalImages() {
        LocalImageUploadWorker.enqueue(workManager)
    }

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
    }

    sealed class SaveState {
        object Idle : SaveState()
        object Success : SaveState()
        data class Error(val message: String) : SaveState()
    }
}
