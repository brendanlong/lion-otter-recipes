package com.lionotter.recipes.ui.screens.addrecipe

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.worker.PaprikaImportWorker
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.observeWorkByTag
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

    // Track last known Paprika import progress for cancellation reporting
    private var lastPaprikaImportedCount: Int = 0
    private var lastPaprikaFailedCount: Int = 0

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
        observePaprikaWorkStatus()
    }

    /**
     * Observe work status for the current import to update this screen's UI state.
     * In-progress recipe cleanup is handled by [InProgressRecipeManager] itself.
     */
    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(RecipeImportWorker.TAG_RECIPE_IMPORT) { currentWorkId }
                .collect { handleWorkInfo(it) }
        }
    }

    private fun observePaprikaWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(PaprikaImportWorker.TAG_PAPRIKA_IMPORT) { currentWorkId }
                .collect { handlePaprikaWorkInfo(it) }
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
                currentImportId = null
                currentWorkId = null
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
                currentImportId = null
                currentWorkId = null
                workManager.pruneWork()
            }
            WorkInfo.State.CANCELLED -> {
                _uiState.value = AddRecipeUiState.Idle
                currentImportId = null
                currentWorkId = null
                workManager.pruneWork()
            }
        }
    }

    private fun handlePaprikaWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
            }
            WorkInfo.State.RUNNING -> {
                val progress = workInfo.progress.getString(PaprikaImportWorker.KEY_PROGRESS)
                val recipeName = workInfo.progress.getString(PaprikaImportWorker.KEY_RECIPE_NAME)
                val current = workInfo.progress.getInt(PaprikaImportWorker.KEY_CURRENT, 0)
                val total = workInfo.progress.getInt(PaprikaImportWorker.KEY_TOTAL, 0)

                // Track running counts for cancellation reporting
                lastPaprikaImportedCount = workInfo.progress.getInt(
                    PaprikaImportWorker.KEY_PROGRESS_IMPORTED_COUNT, lastPaprikaImportedCount
                )
                lastPaprikaFailedCount = workInfo.progress.getInt(
                    PaprikaImportWorker.KEY_PROGRESS_FAILED_COUNT, lastPaprikaFailedCount
                )

                val importProgress = when (progress) {
                    PaprikaImportWorker.PROGRESS_PARSING -> ImportProgress.ParsingPaprikaFile
                    PaprikaImportWorker.PROGRESS_IMPORTING -> ImportProgress.ImportingPaprikaRecipe(
                        recipeName = recipeName ?: "",
                        current = current,
                        total = total
                    )
                    else -> ImportProgress.Starting
                }
                _uiState.value = AddRecipeUiState.Loading(importProgress)
            }
            WorkInfo.State.SUCCEEDED -> {
                val importedCount = workInfo.outputData.getInt(PaprikaImportWorker.KEY_IMPORTED_COUNT, 0)
                val failedCount = workInfo.outputData.getInt(PaprikaImportWorker.KEY_FAILED_COUNT, 0)
                _uiState.value = AddRecipeUiState.PaprikaImportComplete(importedCount, failedCount)
                currentImportId = null
                currentWorkId = null
                lastPaprikaImportedCount = 0
                lastPaprikaFailedCount = 0
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val resultType = workInfo.outputData.getString(PaprikaImportWorker.KEY_RESULT_TYPE)
                val errorMessage = workInfo.outputData.getString(PaprikaImportWorker.KEY_ERROR_MESSAGE)
                    ?: "Unknown error"

                _uiState.value = when (resultType) {
                    PaprikaImportWorker.RESULT_NO_API_KEY -> AddRecipeUiState.NoApiKey
                    else -> AddRecipeUiState.Error(errorMessage)
                }
                currentImportId = null
                currentWorkId = null
                lastPaprikaImportedCount = 0
                lastPaprikaFailedCount = 0
                workManager.pruneWork()
            }
            WorkInfo.State.CANCELLED -> {
                val importedCount = lastPaprikaImportedCount
                if (importedCount > 0) {
                    _uiState.value = AddRecipeUiState.PaprikaImportCancelled(importedCount)
                } else {
                    _uiState.value = AddRecipeUiState.Idle
                }
                currentImportId = null
                currentWorkId = null
                lastPaprikaImportedCount = 0
                lastPaprikaFailedCount = 0
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

        // Build the work request first so we can persist its UUID for cancellation
        val workRequest = OneTimeWorkRequestBuilder<RecipeImportWorker>()
            .setInputData(RecipeImportWorker.createInputData(currentUrl, currentImportId!!))
            .addTag(RecipeImportWorker.TAG_RECIPE_IMPORT)
            .build()

        currentWorkId = workRequest.id
        inProgressRecipeManager.addInProgressRecipe(
            currentImportId!!, "Importing recipe...",
            url = currentUrl,
            workManagerId = workRequest.id.toString()
        )
        workManager.enqueue(workRequest)

        _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
    }

    fun importPaprikaFile(fileUri: Uri, selectedRecipeNames: Set<String>? = null) {
        currentImportId = UUID.randomUUID().toString()
        lastPaprikaImportedCount = 0
        lastPaprikaFailedCount = 0

        val workRequest = OneTimeWorkRequestBuilder<PaprikaImportWorker>()
            .setInputData(PaprikaImportWorker.createInputData(fileUri, selectedRecipeNames))
            .addTag(PaprikaImportWorker.TAG_PAPRIKA_IMPORT)
            .build()

        currentWorkId = workRequest.id
        inProgressRecipeManager.addInProgressRecipe(
            currentImportId!!, "Importing from Paprika...",
            workManagerId = workRequest.id.toString()
        )
        workManager.enqueue(workRequest)

        _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
    }

    fun cancelImport() {
        currentWorkId?.let { workManager.cancelWorkById(it) }
        if (currentImportId != null) {
            inProgressRecipeManager.removeInProgressRecipe(currentImportId!!)
        }

        val importedCount = lastPaprikaImportedCount
        val wasPaprikaImport = importedCount > 0

        currentImportId = null
        currentWorkId = null
        lastPaprikaImportedCount = 0
        lastPaprikaFailedCount = 0

        _uiState.value = if (wasPaprikaImport) {
            AddRecipeUiState.PaprikaImportCancelled(importedCount)
        } else {
            AddRecipeUiState.Idle
        }
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
    object ParsingPaprikaFile : ImportProgress()
    data class ImportingPaprikaRecipe(
        val recipeName: String,
        val current: Int,
        val total: Int
    ) : ImportProgress()
}

sealed class AddRecipeUiState {
    object Idle : AddRecipeUiState()
    data class Loading(val progress: ImportProgress) : AddRecipeUiState()
    data class Success(val recipeId: String) : AddRecipeUiState()
    data class PaprikaImportComplete(val importedCount: Int, val failedCount: Int) : AddRecipeUiState()
    data class PaprikaImportCancelled(val importedCount: Int) : AddRecipeUiState()
    data class Error(val message: String) : AddRecipeUiState()
    object NoApiKey : AddRecipeUiState()
}
