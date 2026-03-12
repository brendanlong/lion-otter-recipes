package com.lionotter.recipes.ui.screens.addrecipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.worker.RecipeImportWorker
import com.lionotter.recipes.worker.TextFileImportWorker
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
        observeWorkStatus()
    }

    /**
     * Observe work status for the current URL import or text file import
     * to update this screen's UI state.
     * In-progress recipe cleanup is handled by [InProgressRecipeManager] itself.
     */
    private fun observeWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(RecipeImportWorker.TAG_RECIPE_IMPORT) { currentWorkId }
                .collect { handleWorkInfo(it, URL_IMPORT_KEYS) }
        }
        viewModelScope.launch {
            workManager.observeWorkByTag(TextFileImportWorker.TAG_TEXT_FILE_IMPORT) { currentWorkId }
                .collect { handleWorkInfo(it, TEXT_FILE_IMPORT_KEYS) }
        }
    }

    /**
     * Maps worker-specific output keys and progress values to UI state.
     */
    private data class WorkerKeys(
        val progressKey: String,
        val recipeIdKey: String,
        val resultTypeKey: String,
        val errorMessageKey: String,
        val noApiKeyValue: String,
        val progressMapping: (String?) -> ImportProgress
    )

    private fun handleWorkInfo(workInfo: WorkInfo, keys: WorkerKeys) {
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
            }
            WorkInfo.State.RUNNING -> {
                val progress = workInfo.progress.getString(keys.progressKey)
                _uiState.value = AddRecipeUiState.Loading(keys.progressMapping(progress))
            }
            WorkInfo.State.SUCCEEDED -> {
                val recipeId = workInfo.outputData.getString(keys.recipeIdKey)
                if (recipeId != null) {
                    _uiState.value = AddRecipeUiState.Success(recipeId)
                }
                currentImportId = null
                currentWorkId = null
                workManager.pruneWork()
            }
            WorkInfo.State.FAILED -> {
                val resultType = workInfo.outputData.getString(keys.resultTypeKey)
                val errorMessage = workInfo.outputData.getString(keys.errorMessageKey)
                    ?: "Unknown error"
                _uiState.value = when (resultType) {
                    keys.noApiKeyValue -> AddRecipeUiState.NoApiKey
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

    companion object {
        private val URL_IMPORT_KEYS = WorkerKeys(
            progressKey = RecipeImportWorker.KEY_PROGRESS,
            recipeIdKey = RecipeImportWorker.KEY_RECIPE_ID,
            resultTypeKey = RecipeImportWorker.KEY_RESULT_TYPE,
            errorMessageKey = RecipeImportWorker.KEY_ERROR_MESSAGE,
            noApiKeyValue = RecipeImportWorker.RESULT_NO_API_KEY,
            progressMapping = { progress ->
                when (progress) {
                    RecipeImportWorker.PROGRESS_FETCHING -> ImportProgress.FetchingPage
                    RecipeImportWorker.PROGRESS_PARSING -> ImportProgress.ParsingRecipe
                    RecipeImportWorker.PROGRESS_SAVING -> ImportProgress.SavingRecipe
                    else -> ImportProgress.Starting
                }
            }
        )

        private val TEXT_FILE_IMPORT_KEYS = WorkerKeys(
            progressKey = TextFileImportWorker.KEY_PROGRESS,
            recipeIdKey = TextFileImportWorker.KEY_RECIPE_ID,
            resultTypeKey = TextFileImportWorker.KEY_RESULT_TYPE,
            errorMessageKey = TextFileImportWorker.KEY_ERROR_MESSAGE,
            noApiKeyValue = TextFileImportWorker.RESULT_NO_API_KEY,
            progressMapping = { progress ->
                when (progress) {
                    TextFileImportWorker.PROGRESS_READING_FILE -> ImportProgress.FetchingPage
                    TextFileImportWorker.PROGRESS_PARSING -> ImportProgress.ParsingRecipe
                    TextFileImportWorker.PROGRESS_SAVING -> ImportProgress.SavingRecipe
                    else -> ImportProgress.Starting
                }
            }
        )
    }

    /**
     * Import a recipe from a text file (.md, .txt, .html).
     * Reads the file content and sends it to the AI for parsing.
     */
    fun importFromFile(fileUri: String, fileType: String) {
        // Guard against double-enqueue (e.g., from LaunchedEffect recomposition)
        if (currentImportId != null) return

        currentImportId = UUID.randomUUID().toString()

        val workRequest = OneTimeWorkRequestBuilder<TextFileImportWorker>()
            .setInputData(
                TextFileImportWorker.createInputData(fileUri, fileType, currentImportId!!)
            )
            .addTag(TextFileImportWorker.TAG_TEXT_FILE_IMPORT)
            .build()

        currentWorkId = workRequest.id
        inProgressRecipeManager.addInProgressRecipe(
            currentImportId!!, "Importing recipe from file\u2026",
            workManagerId = workRequest.id.toString()
        )
        workManager.enqueue(workRequest)

        _uiState.value = AddRecipeUiState.Loading(ImportProgress.Queued)
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
