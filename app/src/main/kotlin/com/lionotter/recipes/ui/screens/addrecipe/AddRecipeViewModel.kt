package com.lionotter.recipes.ui.screens.addrecipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.worker.RecipeImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AddRecipeViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val inProgressRecipeManager: InProgressRecipeManager,
    settingsDataStore: SettingsDataStore
) : ViewModel() {

    // Track the current import started from this screen
    private var currentImportId: String? = null
    private var currentWorkId: UUID? = null

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
        // Observe any ongoing import work by tag
        observeWorkStatus()
    }

    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(RecipeImportWorker.TAG_RECIPE_IMPORT)
                .collect { workInfos ->
                    // Find the work info for our current import
                    val currentWorkInfo = currentWorkId?.let { workId ->
                        workInfos.find { it.id == workId }
                    }
                    if (currentWorkInfo != null) {
                        handleWorkInfo(currentWorkInfo)
                    }

                    // Update in-progress recipes for all running imports
                    workInfos.forEach { workInfo ->
                        val importId = workInfo.progress.getString(RecipeImportWorker.KEY_IMPORT_ID)
                            ?: workInfo.outputData.getString(RecipeImportWorker.KEY_IMPORT_ID)

                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                val recipeName = workInfo.progress.getString(RecipeImportWorker.KEY_RECIPE_NAME)
                                if (recipeName != null && importId != null) {
                                    inProgressRecipeManager.updateRecipeName(importId, recipeName)
                                }
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                if (importId != null) {
                                    inProgressRecipeManager.removeInProgressRecipe(importId)
                                }
                            }
                            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                                if (importId != null) {
                                    inProgressRecipeManager.removeInProgressRecipe(importId)
                                }
                            }
                            else -> {}
                        }
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
                val recipeName = workInfo.progress.getString(RecipeImportWorker.KEY_RECIPE_NAME)

                val importProgress = when (progress) {
                    RecipeImportWorker.PROGRESS_FETCHING -> ImportProgress.FetchingPage
                    RecipeImportWorker.PROGRESS_PARSING -> ImportProgress.ParsingRecipe
                    RecipeImportWorker.PROGRESS_SAVING -> ImportProgress.SavingRecipe
                    else -> ImportProgress.Starting
                }
                _uiState.value = AddRecipeUiState.Loading(importProgress)

                // If we have a recipe name, update the in-progress state
                if (recipeName != null && currentImportId != null) {
                    inProgressRecipeManager.updateRecipeName(currentImportId!!, recipeName)
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                val recipeId = workInfo.outputData.getString(RecipeImportWorker.KEY_RECIPE_ID)
                if (recipeId != null) {
                    _uiState.value = AddRecipeUiState.Success(recipeId)
                    // Remove from in-progress once completed
                    if (currentImportId != null) {
                        inProgressRecipeManager.removeInProgressRecipe(currentImportId!!)
                    }
                }
                currentImportId = null
                currentWorkId = null
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
                // Remove from in-progress on failure
                if (currentImportId != null) {
                    inProgressRecipeManager.removeInProgressRecipe(currentImportId!!)
                }
                currentImportId = null
                currentWorkId = null
                // Prune completed work
                workManager.pruneWork()
            }
            WorkInfo.State.CANCELLED -> {
                _uiState.value = AddRecipeUiState.Idle
                if (currentImportId != null) {
                    inProgressRecipeManager.removeInProgressRecipe(currentImportId!!)
                }
                currentImportId = null
                currentWorkId = null
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

        // Generate ID for tracking this import in the recipe list
        currentImportId = UUID.randomUUID().toString()
        inProgressRecipeManager.addInProgressRecipe(currentImportId!!, "Importing recipe...")

        // Enqueue the work with a tag for tracking multiple concurrent imports
        val workRequest = OneTimeWorkRequestBuilder<RecipeImportWorker>()
            .setInputData(RecipeImportWorker.createInputData(currentUrl, currentImportId!!))
            .addTag(RecipeImportWorker.TAG_RECIPE_IMPORT)
            .build()

        currentWorkId = workRequest.id
        workManager.enqueue(workRequest)

        _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
    }

    fun cancelImport() {
        currentWorkId?.let { workManager.cancelWorkById(it) }
        if (currentImportId != null) {
            inProgressRecipeManager.removeInProgressRecipe(currentImportId!!)
        }
        currentImportId = null
        currentWorkId = null
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
