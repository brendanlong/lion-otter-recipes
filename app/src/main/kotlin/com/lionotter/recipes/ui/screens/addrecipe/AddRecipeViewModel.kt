package com.lionotter.recipes.ui.screens.addrecipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.domain.usecase.ImportRecipeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddRecipeViewModel @Inject constructor(
    private val importRecipeUseCase: ImportRecipeUseCase,
    settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _uiState = MutableStateFlow<AddRecipeUiState>(AddRecipeUiState.Idle)
    val uiState: StateFlow<AddRecipeUiState> = _uiState.asStateFlow()

    val hasApiKey: StateFlow<Boolean> = settingsDataStore.anthropicApiKey
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun onUrlChange(url: String) {
        _url.value = url
    }

    fun importRecipe() {
        val currentUrl = _url.value.trim()
        if (currentUrl.isBlank()) {
            _uiState.value = AddRecipeUiState.Error("Please enter a URL")
            return
        }

        if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) {
            _uiState.value = AddRecipeUiState.Error("URL must start with http:// or https://")
            return
        }

        viewModelScope.launch {
            _uiState.value = AddRecipeUiState.Loading(ImportRecipeUseCase.ImportProgress.FetchingPage)

            val result = importRecipeUseCase.execute(
                url = currentUrl,
                onProgress = { progress ->
                    _uiState.value = AddRecipeUiState.Loading(progress)
                }
            )

            _uiState.value = when (result) {
                is ImportRecipeUseCase.ImportResult.Success -> {
                    AddRecipeUiState.Success(result.recipe.id)
                }
                is ImportRecipeUseCase.ImportResult.Error -> {
                    AddRecipeUiState.Error(result.message)
                }
                ImportRecipeUseCase.ImportResult.NoApiKey -> {
                    AddRecipeUiState.NoApiKey
                }
            }
        }
    }

    fun resetState() {
        _uiState.value = AddRecipeUiState.Idle
    }
}

sealed class AddRecipeUiState {
    object Idle : AddRecipeUiState()
    data class Loading(val progress: ImportRecipeUseCase.ImportProgress) : AddRecipeUiState()
    data class Success(val recipeId: String) : AddRecipeUiState()
    data class Error(val message: String) : AddRecipeUiState()
    object NoApiKey : AddRecipeUiState()
}
