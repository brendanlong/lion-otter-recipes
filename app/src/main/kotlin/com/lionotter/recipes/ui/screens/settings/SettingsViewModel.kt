package com.lionotter.recipes.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val importDebugRepository: ImportDebugRepository
) : ViewModel() {

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

    val extendedThinkingEnabled: StateFlow<Boolean> = settingsDataStore.extendedThinkingEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
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
                settingsDataStore.setAnthropicApiKey(key)
                _apiKeyInput.value = ""
                _saveState.value = SaveState.Success
            } catch (e: Exception) {
                _saveState.value = SaveState.Error("Failed to save: ${e.message}")
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            settingsDataStore.clearAnthropicApiKey()
            _saveState.value = SaveState.Idle
        }
    }

    fun setAiModel(model: String) {
        viewModelScope.launch {
            settingsDataStore.setAiModel(model)
        }
    }

    fun setExtendedThinkingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setExtendedThinkingEnabled(enabled)
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setKeepScreenOn(enabled)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsDataStore.setThemeMode(mode)
        }
    }

    fun setVolumeUnitSystem(system: UnitSystem) {
        viewModelScope.launch {
            settingsDataStore.setVolumeUnitSystem(system)
        }
    }

    fun setWeightUnitSystem(system: UnitSystem) {
        viewModelScope.launch {
            settingsDataStore.setWeightUnitSystem(system)
        }
    }

    fun setStartOfWeek(startOfWeek: StartOfWeek) {
        viewModelScope.launch {
            settingsDataStore.setStartOfWeek(startOfWeek)
        }
    }

    fun setImportDebuggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setImportDebuggingEnabled(enabled)
            if (!enabled) {
                importDebugRepository.deleteAllDebugEntries()
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
