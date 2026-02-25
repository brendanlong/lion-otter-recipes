package com.lionotter.recipes.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.AccountMigrationService
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.ImageSyncService
import com.lionotter.recipes.data.remote.LinkResult
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val recipeRepository: IRecipeRepository,
    private val imageSyncService: ImageSyncService,
    private val accountMigrationService: AccountMigrationService
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val authState: StateFlow<AuthState> = authService.authState

    val currentUserEmail: StateFlow<String?> = authService.currentUserEmail

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

    private val _isLinking = MutableStateFlow(false)
    val isLinking: StateFlow<Boolean> = _isLinking.asStateFlow()

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
            } catch (e: Exception) {
                _saveState.value = SaveState.Error("Failed to save: ${e.message}")
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                settingsDataStore.clearAnthropicApiKey()
            }
            _saveState.value = SaveState.Idle
        }
    }

    fun setAiModel(model: String) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setAiModel(model) }
        }
    }

    fun setEditModel(model: String) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setEditModel(model) }
        }
    }

    fun setThinkingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setThinkingEnabled(enabled) }
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setKeepScreenOn(enabled) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setThemeMode(mode) }
        }
    }

    fun setVolumeUnitSystem(system: UnitSystem) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setVolumeUnitSystem(system) }
        }
    }

    fun setWeightUnitSystem(system: UnitSystem) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setWeightUnitSystem(system) }
        }
    }

    fun setGroceryVolumeUnitSystem(system: UnitSystem) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setGroceryVolumeUnitSystem(system) }
        }
    }

    fun setGroceryWeightUnitSystem(system: UnitSystem) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setGroceryWeightUnitSystem(system) }
        }
    }

    fun setStartOfWeek(startOfWeek: StartOfWeek) {
        viewModelScope.launch {
            withContext(NonCancellable) { settingsDataStore.setStartOfWeek(startOfWeek) }
        }
    }

    fun setImportDebuggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setImportDebuggingEnabled(enabled)
                if (!enabled) {
                    importDebugRepository.deleteAllDebugEntries()
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            withContext(NonCancellable) { authService.signOut() }
        }
    }

    /**
     * Deletes the user's account and all associated data (recipes, meal plans,
     * images) from Firebase, then transitions to anonymous mode.
     */
    fun deleteAccount() {
        viewModelScope.launch {
            _isDeletingAccount.value = true
            try {
                withContext(NonCancellable) {
                    authService.deleteAccountAndData()
                }
                _toastMessage.tryEmit(R.string.delete_account_success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete account", e)
                _toastMessage.tryEmit(R.string.delete_account_failed)
            } finally {
                _isDeletingAccount.value = false
            }
        }
    }

    /**
     * Links the current anonymous account with Google.
     * Handles both the happy path (link succeeds) and the merge path
     * (Google account already exists, data migration needed).
     */
    fun linkWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            _isLinking.value = true
            try {
                val result = withContext(NonCancellable) {
                    authService.linkWithGoogle(activityContext)
                }
                result.fold(
                    onSuccess = { linkResult ->
                        when (linkResult) {
                            is LinkResult.Linked -> {
                                // Upload local images in the background
                                uploadLocalImages()
                                _toastMessage.tryEmit(R.string.signed_in_with_google)
                            }
                            is LinkResult.NeedsMerge -> {
                                performMerge(linkResult)
                            }
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Failed to link Google account", e)
                        _toastMessage.tryEmit(R.string.sign_in_failed)
                    }
                )
            } finally {
                _isLinking.value = false
            }
        }
    }

    /**
     * Performs data migration from anonymous account to existing Google account.
     *
     * Uses a secondary FirebaseApp to sign in as Google and write guest data
     * to the Google account while the anonymous account remains intact on the
     * default app. Only after the migration succeeds does it switch the default
     * app to the Google user.
     */
    private suspend fun performMerge(mergeResult: LinkResult.NeedsMerge) {
        try {
            withContext(NonCancellable) {
                authService.beginMergeTransition()
                val result = accountMigrationService.migrateGuestData(mergeResult.credential)
                if (result.isFailure) {
                    Log.e(TAG, "Migration failed", result.exceptionOrNull())
                    // Migration failed — still anonymous, restore auth state
                    authService.endMergeTransition()
                    _toastMessage.tryEmit(R.string.sign_in_failed)
                    return@withContext
                }
                authService.completeMergeSignIn()
                _toastMessage.tryEmit(R.string.signed_in_with_google)
            }

            // Upload local images in the background
            uploadLocalImages()
        } catch (e: Exception) {
            Log.e(TAG, "Error during account merge", e)
            // Restore auth state based on current user in case of unexpected error
            authService.endMergeTransition()
            _toastMessage.tryEmit(R.string.sign_in_migration_warning)
        }
    }

    /**
     * Uploads all local file:// images to Firebase Storage in the background.
     * Called after linking/merging a Google account.
     */
    private fun uploadLocalImages() {
        viewModelScope.launch {
            try {
                val recipes = recipeRepository.getAllRecipeIdsAndNames()
                for (idAndName in recipes) {
                    val recipe = recipeRepository.getRecipeByIdOnce(idAndName.id) ?: continue
                    val imageUrl = recipe.imageUrl ?: continue
                    if (imageUrl.startsWith("file://")) {
                        val gsUri = imageSyncService.uploadImage(imageUrl)
                        if (gsUri != null) {
                            recipeRepository.setImageUrl(idAndName.id, gsUri)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading local images after account link", e)
            }
        }
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
