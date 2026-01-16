package com.lionotter.recipes.ui.screens.addrecipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.worker.RecipeImportWorker
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
    private val workManager: WorkManager,
    settingsDataStore: SettingsDataStore
) : ViewModel() {

    companion object {
        const val WORK_NAME = "recipe_import"
    }

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

    init {
        // Observe any ongoing import work
        observeWorkStatus()
    }

    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .collect { workInfos ->
                    val workInfo = workInfos.firstOrNull()
                    if (workInfo != null) {
                        handleWorkInfo(workInfo)
                    }
                }
        }
    }

    private fun handleWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
            }
            WorkInfo.State.RUNNING -> {
                val progress = workInfo.progress.getString(RecipeImportWorker.KEY_PROGRESS)
                val importProgress = when (progress) {
                    RecipeImportWorker.PROGRESS_FETCHING -> ImportProgress.FetchingPage
                    RecipeImportWorker.PROGRESS_PARSING -> ImportProgress.ParsingRecipe
                    RecipeImportWorker.PROGRESS_SAVING -> ImportProgress.SavingRecipe
                    else -> ImportProgress.Starting
                }
                _uiState.value = AddRecipeUiState.Loading(importProgress)
            }
            WorkInfo.State.SUCCEEDED -> {
                val recipeId = workInfo.outputData.getString(RecipeImportWorker.KEY_RECIPE_ID)
                if (recipeId != null) {
                    _uiState.value = AddRecipeUiState.Success(recipeId)
                }
                // Prune completed work
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val resultType = workInfo.outputData.getString(RecipeImportWorker.KEY_RESULT_TYPE)
                val errorMessage = workInfo.outputData.getString(RecipeImportWorker.KEY_ERROR_MESSAGE)
                    ?: "Unknown error"

                _uiState.value = when (resultType) {
                    RecipeImportWorker.RESULT_NO_API_KEY -> AddRecipeUiState.NoApiKey
                    else -> AddRecipeUiState.Error(errorMessage)
                }
                // Prune completed work
                workManager.pruneWork()
            }
            WorkInfo.State.CANCELLED -> {
                _uiState.value = AddRecipeUiState.Idle
                workManager.pruneWork()
            }
        }
    }

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

        // Enqueue the work
        val workRequest = OneTimeWorkRequestBuilder<RecipeImportWorker>()
            .setInputData(RecipeImportWorker.createInputData(currentUrl))
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
    }

    fun cancelImport() {
        workManager.cancelUniqueWork(WORK_NAME)
        _uiState.value = AddRecipeUiState.Idle
    }

    fun resetState() {
        _uiState.value = AddRecipeUiState.Idle
    }
}

sealed class ImportProgress {
    object Queued : ImportProgress()
    object Starting : ImportProgress()
    object FetchingPage : ImportProgress()
    object ParsingRecipe : ImportProgress()
    object SavingRecipe : ImportProgress()
}

sealed class AddRecipeUiState {
    object Idle : AddRecipeUiState()
    data class Loading(val progress: ImportProgress) : AddRecipeUiState()
    data class Success(val recipeId: String) : AddRecipeUiState()
    data class Error(val message: String) : AddRecipeUiState()
    object NoApiKey : AddRecipeUiState()
}
