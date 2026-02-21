package com.lionotter.recipes.ui.screens.editrecipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import com.lionotter.recipes.worker.RecipeEditWorker
import com.lionotter.recipes.worker.RecipeRegenerateWorker
import com.lionotter.recipes.worker.observeWorkByTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed class EditUiState {
    object Idle : EditUiState()
    data class Loading(val progress: String) : EditUiState()
    object Success : EditUiState()
    data class Error(val message: String) : EditUiState()
}

@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val settingsDataStore: SettingsDataStore,
    private val workManager: WorkManager
) : ViewModel() {

    private val recipeId: String = savedStateHandle.get<String>("recipeId")
        ?: throw IllegalArgumentException("EditRecipeViewModel requires a 'recipeId' argument in SavedStateHandle")

    val recipe: StateFlow<Recipe?> = recipeRepository.getRecipeById(recipeId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val _markdownText = MutableStateFlow("")
    val markdownText: StateFlow<String> = _markdownText.asStateFlow()

    private val _editState = MutableStateFlow<EditUiState>(EditUiState.Idle)
    val editState: StateFlow<EditUiState> = _editState.asStateFlow()

    private val _model = MutableStateFlow(AnthropicService.DEFAULT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _extendedThinking = MutableStateFlow(true)
    val extendedThinking: StateFlow<Boolean> = _extendedThinking.asStateFlow()

    private val _hasOriginalHtml = MutableStateFlow(false)
    val hasOriginalHtml: StateFlow<Boolean> = _hasOriginalHtml.asStateFlow()

    /**
     * Whether regeneration from original source is available.
     * True if the recipe has cached original HTML or a source URL to re-fetch from.
     */
    val canRegenerate: StateFlow<Boolean> = combine(
        _hasOriginalHtml,
        recipe
    ) { hasHtml, recipe ->
        hasHtml || !recipe?.sourceUrl.isNullOrBlank()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    private var currentEditWorkId: UUID? = null
    private var currentRegenerateWorkId: UUID? = null

    fun setMarkdownText(text: String) {
        _markdownText.value = text
    }

    fun setModel(model: String) {
        _model.value = model
    }

    fun setExtendedThinking(enabled: Boolean) {
        _extendedThinking.value = enabled
    }

    fun resetEditState() {
        _editState.value = EditUiState.Idle
    }

    fun cancelProcessing() {
        currentEditWorkId?.let { workManager.cancelWorkById(it) }
        currentRegenerateWorkId?.let { workManager.cancelWorkById(it) }
    }

    /**
     * Save the edited markdown by sending it through AI for re-parsing.
     */
    fun saveEdits() {
        val workRequest = OneTimeWorkRequestBuilder<RecipeEditWorker>()
            .setInputData(
                RecipeEditWorker.createInputData(
                    recipeId = recipeId,
                    markdownText = _markdownText.value,
                    model = _model.value,
                    extendedThinking = _extendedThinking.value
                )
            )
            .addTag(RecipeEditWorker.TAG_RECIPE_EDIT)
            .build()

        currentEditWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _editState.value = EditUiState.Loading("Preparing...")
    }

    /**
     * Regenerate the recipe from the original source HTML/URL.
     */
    fun regenerateFromOriginal() {
        val workRequest = OneTimeWorkRequestBuilder<RecipeRegenerateWorker>()
            .setInputData(
                RecipeRegenerateWorker.createInputData(
                    recipeId = recipeId,
                    model = _model.value,
                    extendedThinking = _extendedThinking.value
                )
            )
            .addTag(RecipeRegenerateWorker.TAG_RECIPE_REGENERATE)
            .build()

        currentRegenerateWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _editState.value = EditUiState.Loading("Preparing...")
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            // Load settings defaults
            _model.value = settingsDataStore.aiModel.first()
            _extendedThinking.value = settingsDataStore.extendedThinkingEnabled.first()

            // Check for original HTML
            val html = recipeRepository.getOriginalHtml(recipeId)
            _hasOriginalHtml.value = !html.isNullOrBlank()

            // Load recipe and generate markdown (only if user hasn't started typing)
            val currentRecipe = recipe.first { it != null } ?: return@launch
            if (_markdownText.value.isBlank()) {
                _markdownText.value = RecipeMarkdownFormatter.format(currentRecipe)
            }
        }
    }

    private fun observeEditWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(RecipeEditWorker.TAG_RECIPE_EDIT) { currentEditWorkId }
                .collect { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            _editState.value = EditUiState.Loading("Preparing...")
                        }
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getString(RecipeEditWorker.KEY_PROGRESS)
                            val message = when (progress) {
                                RecipeEditWorker.PROGRESS_PARSING -> "AI is processing your changes..."
                                RecipeEditWorker.PROGRESS_SAVING -> "Saving recipe..."
                                else -> "Starting..."
                            }
                            _editState.value = EditUiState.Loading(message)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _editState.value = EditUiState.Success
                            currentEditWorkId = null
                            workManager.pruneWork()
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMessage = workInfo.outputData.getString(RecipeEditWorker.KEY_ERROR_MESSAGE)
                                ?: "Unknown error"
                            _editState.value = EditUiState.Error(errorMessage)
                            currentEditWorkId = null
                            workManager.pruneWork()
                        }
                        WorkInfo.State.CANCELLED -> {
                            _editState.value = EditUiState.Idle
                            currentEditWorkId = null
                            workManager.pruneWork()
                        }
                    }
                }
        }
    }

    private fun observeRegenerateWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(RecipeRegenerateWorker.TAG_RECIPE_REGENERATE) { currentRegenerateWorkId }
                .collect { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            _editState.value = EditUiState.Loading("Preparing...")
                        }
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getString(RecipeRegenerateWorker.KEY_PROGRESS)
                            val message = when (progress) {
                                RecipeRegenerateWorker.PROGRESS_FETCHING -> "Fetching recipe page..."
                                RecipeRegenerateWorker.PROGRESS_PARSING -> "AI is re-analyzing..."
                                RecipeRegenerateWorker.PROGRESS_SAVING -> "Saving recipe..."
                                else -> "Starting..."
                            }
                            _editState.value = EditUiState.Loading(message)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _editState.value = EditUiState.Success
                            currentRegenerateWorkId = null
                            workManager.pruneWork()
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMessage = workInfo.outputData.getString(RecipeRegenerateWorker.KEY_ERROR_MESSAGE)
                                ?: "Unknown error"
                            _editState.value = EditUiState.Error(errorMessage)
                            currentRegenerateWorkId = null
                            workManager.pruneWork()
                        }
                        WorkInfo.State.CANCELLED -> {
                            _editState.value = EditUiState.Idle
                            currentRegenerateWorkId = null
                            workManager.pruneWork()
                        }
                    }
                }
        }
    }

    init {
        loadInitialData()
        observeEditWorkStatus()
        observeRegenerateWorkStatus()
    }
}
