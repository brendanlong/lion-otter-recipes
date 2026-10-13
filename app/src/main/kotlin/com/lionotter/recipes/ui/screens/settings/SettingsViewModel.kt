package com.lionotter.recipes.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.AuthCredential
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.ai.ApiKeyProblem
import com.lionotter.recipes.data.ai.ApiKeyValidator
import com.lionotter.recipes.data.remote.AccountMigrationService
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.ReauthenticationRequiredException
import com.lionotter.recipes.data.remote.SignOutResult
import com.lionotter.recipes.data.remote.AccountDeletionService
import com.lionotter.recipes.data.remote.ImageUploadScheduler
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.util.ErrorLog
import androidx.credentials.exceptions.GetCredentialCancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val imageUploadScheduler: ImageUploadScheduler
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
            initialValue = AiModels.DEFAULT_MODEL
        )

    val editModel: StateFlow<String> = settingsDataStore.editModel
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiModels.DEFAULT_EDIT_MODEL
        )

    val thinkingEnabled: StateFlow<Boolean> = settingsDataStore.thinkingEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiModels.DEFAULT_THINKING_ENABLED
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

    /** Why the entered API key couldn't be saved, shown under the field until it is edited. */
    private val _apiKeyError = MutableStateFlow<UserMessage?>(null)
    val apiKeyError: StateFlow<UserMessage?> = _apiKeyError.asStateFlow()

    /** The account operation in progress, if any; only one runs at a time. */
    private val _busyOp = MutableStateFlow<AccountOp?>(null)
    val busyOp: StateFlow<AccountOp?> = _busyOp.asStateFlow()

    /** Messages to show as toasts. */
    private val _events = Channel<UserMessage>(Channel.BUFFERED)
    val events: Flow<UserMessage> = _events.receiveAsFlow()

    fun onApiKeyInputChange(input: String) {
        _apiKeyInput.value = input
        _apiKeyError.value = null
    }

    fun saveApiKey() {
        val key = _apiKeyInput.value.trim()

        when (ApiKeyValidator.validate(key)) {
            ApiKeyProblem.EMPTY -> _apiKeyError.value = UserMessage(R.string.error_api_key_empty)
            ApiKeyProblem.BAD_PREFIX -> _apiKeyError.value = UserMessage(R.string.error_api_key_prefix, ApiKeyValidator.API_KEY_PREFIX)
            null -> viewModelScope.launch {
                try {
                    withContext(NonCancellable) {
                        settingsDataStore.setAnthropicApiKey(key)
                    }
                    _apiKeyInput.value = ""
                    _apiKeyError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.e(TAG, "Failed to save API key", e)
                    _apiKeyError.value = UserMessage(R.string.error_api_key_save)
                }
            }
        }
    }

    fun clearApiKey() {
        launchSettingWrite("clear API key") { settingsDataStore.clearAnthropicApiKey() }
    }

    /**
     * Runs a settings write that survives viewModelScope cancellation. On failure, logs and
     * shows a toast so the user knows the change didn't stick.
     */
    private fun launchSettingWrite(description: String, write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { write() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to $description", e)
                _events.send(UserMessage(R.string.settings_save_failed))
            }
        }
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

    /** Claims the account-operation slot for [op]; false if another operation is running. */
    private fun begin(op: AccountOp): Boolean = _busyOp.compareAndSet(null, op)

    private fun finish(op: AccountOp) {
        _busyOp.compareAndSet(op, null)
    }

    /**
     * Signs out unless local changes haven't synced yet, in which case the UI is asked
     * to confirm via [showUnsyncedSignOutWarning] before [signOut] is called with [force].
     */
    fun signOut(force: Boolean = false) {
        _showUnsyncedSignOutWarning.value = false
        if (!begin(AccountOp.SIGNING_OUT)) return
        viewModelScope.launch {
            try {
                val result = withContext(NonCancellable) { authService.signOut(force) }
                if (result is SignOutResult.PendingWrites) _showUnsyncedSignOutWarning.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to sign out", e)
                _events.send(UserMessage(R.string.error_sign_out))
            } finally {
                finish(AccountOp.SIGNING_OUT)
            }
        }
    }

    fun dismissUnsyncedSignOutWarning() {
        _showUnsyncedSignOutWarning.value = false
    }

    /**
     * Starts account deletion. Returns false if another account operation is running; otherwise
     * the UI gets a credential with [requestReauthCredential] and must pass the result to
     * [deleteAccount] (including [CredentialResult.Cancelled]).
     */
    fun beginDeleteAccount(): Boolean = begin(AccountOp.DELETING_ACCOUNT)

    /**
     * Requests a fresh Google credential so the account can be deleted. Must be called
     * from a UI-owned coroutine scope, since [activityContext] must not outlive the Activity.
     */
    suspend fun requestReauthCredential(activityContext: Context): CredentialResult = try {
        CredentialResult.Obtained(authService.requestReauthCredential(activityContext))
    } catch (e: ReauthenticationRequiredException) {
        if (e.cause is GetCredentialCancellationException) CredentialResult.Cancelled else CredentialResult.Failed(e)
    }

    /**
     * Deletes the user's account and all associated data (recipes, meal plans,
     * images) from Firebase, then transitions to guest mode. Without a fresh credential
     * ([CredentialResult.Failed]) deletion only works if the last sign-in was recent.
     */
    fun deleteAccount(reauth: CredentialResult) {
        val credential = when (reauth) {
            CredentialResult.Cancelled -> {
                finish(AccountOp.DELETING_ACCOUNT)
                return
            }
            is CredentialResult.Failed -> null
            is CredentialResult.Obtained -> reauth.credential
        }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    authService.deleteAccountAndData(credential)
                }
                _events.send(UserMessage(R.string.delete_account_success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to delete account", e)
                _events.send(
                    UserMessage(
                        when (e) {
                            is ReauthenticationRequiredException -> R.string.delete_account_reauth_required
                            is AccountDeletionService.OfflineException -> R.string.delete_account_offline
                            is AccountDeletionService.AuthAccountDeletionException ->
                                R.string.delete_account_auth_failed
                            else -> R.string.delete_account_failed
                        }
                    )
                )
            } finally {
                finish(AccountOp.DELETING_ACCOUNT)
            }
        }
    }

    /**
     * Starts Google sign-in. Returns false if another account operation is running; otherwise
     * the UI gets a credential with [requestGoogleCredential] (in its own scope, so the Activity
     * context is never captured by a ViewModel coroutine) and must pass the result to
     * [completeGoogleSignIn] (including [CredentialResult.Cancelled]).
     */
    fun beginGoogleSignIn(): Boolean = begin(AccountOp.SIGNING_IN)

    /**
     * Requests a Google credential via Credential Manager. Must be called from a UI-owned
     * coroutine scope, since [activityContext] must not outlive the Activity.
     */
    suspend fun requestGoogleCredential(activityContext: Context): CredentialResult =
        authService.signInWithGoogle(activityContext).fold(
            onSuccess = { CredentialResult.Obtained(it) },
            onFailure = { e ->
                if (e is GetCredentialCancellationException) CredentialResult.Cancelled else CredentialResult.Failed(e)
            }
        )

    /**
     * Completes Google sign-in with the credential obtained by [requestGoogleCredential]:
     * migrates guest data to the Google account, then finishes the sign-in.
     */
    fun completeGoogleSignIn(result: CredentialResult) {
        viewModelScope.launch {
            try {
                when (result) {
                    CredentialResult.Cancelled -> Unit
                    is CredentialResult.Failed -> {
                        ErrorLog.e(TAG, "Failed to get Google credential", result.error)
                        _events.send(UserMessage(R.string.sign_in_failed))
                    }
                    is CredentialResult.Obtained -> performMigration(result.credential)
                }
            } finally {
                finish(AccountOp.SIGNING_IN)
            }
        }
    }

    /**
     * Migrates guest data to the Google account, then completes the sign-in.
     */
    private suspend fun performMigration(credential: AuthCredential) {
        val message = try {
            val result = withContext(NonCancellable) {
                authService.mergeGuestIntoGoogle { guestUid ->
                    accountMigrationService.migrateGuestData(credential, guestUid)
                }
            }
            if (result.isSuccess) {
                // Uploads images picked as a guest (still file://) to the newly linked account.
                imageUploadScheduler.scheduleLocalImageUpload()
                R.string.signed_in_with_google
            } else {
                ErrorLog.e(TAG, "Migration failed", result.exceptionOrNull())
                R.string.sign_in_failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Error during account migration", e)
            R.string.sign_in_migration_warning
        }
        _events.send(UserMessage(message))
    }
}

/** An account operation that shows progress and blocks starting another. */
enum class AccountOp { SIGNING_IN, SIGNING_OUT, DELETING_ACCOUNT }

/** The outcome of asking the user for a Google credential (to sign in or re-authenticate). */
sealed interface CredentialResult {
    data class Obtained(val credential: AuthCredential) : CredentialResult
    data object Cancelled : CredentialResult
    data class Failed(val error: Throwable) : CredentialResult
}
