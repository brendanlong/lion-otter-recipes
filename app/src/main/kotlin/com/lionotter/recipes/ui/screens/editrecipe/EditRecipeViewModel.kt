package com.lionotter.recipes.ui.screens.editrecipe

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.IRecipeRepository
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

sealed class EditUiState {
    object Idle : EditUiState()
    data class Loading(val progress: String) : EditUiState()
    data class Success(val newRecipeId: String? = null) : EditUiState()
    data class Error(val message: String) : EditUiState()
}

@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeRepository: IRecipeRepository,
    private val settingsDataStore: SettingsDataStore,
    private val workManager: WorkManager,
    private val imageDownloadService: ImageDownloadService
) : ViewModel() {

    private val recipeId: String = savedStateHandle.get<String>("recipeId")
        ?: throw IllegalArgumentException("EditRecipeViewModel requires a 'recipeId' argument in SavedStateHandle")

    val recipe: StateFlow<Recipe?> = recipeRepository.getRecipeById(recipeId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val _imageUrl = MutableStateFlow<String?>(null)
    val imageUrl: StateFlow<String?> = _imageUrl.asStateFlow()

    /** Whether the user has explicitly changed the image (pick or remove). */
    private var imageChanged = false

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _sourceUrl = MutableStateFlow<String?>(null)
    val sourceUrl: StateFlow<String?> = _sourceUrl.asStateFlow()

    private val _markdownText = MutableStateFlow("")
    val markdownText: StateFlow<String> = _markdownText.asStateFlow()

    private val _aiInstructions = MutableStateFlow("")
    val aiInstructions: StateFlow<String> = _aiInstructions.asStateFlow()

    /** The original title loaded from the recipe, used to detect changes. */
    private var originalTitle: String = ""

    /** The original source URL loaded from the recipe, used to detect changes. */
    private var originalSourceUrl: String? = null

    /** The original markdown body loaded from the recipe, used to detect changes. */
    private var originalMarkdownText: String = ""

    private val _editState = MutableStateFlow<EditUiState>(EditUiState.Idle)
    val editState: StateFlow<EditUiState> = _editState.asStateFlow()

    private val _model = MutableStateFlow(AnthropicService.DEFAULT_EDIT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(SettingsDataStore.DEFAULT_THINKING_ENABLED)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

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

    fun setTitle(title: String) {
        _title.value = title
    }

    fun setSourceUrl(url: String?) {
        _sourceUrl.value = url
    }

    fun setMarkdownText(text: String) {
        _markdownText.value = text
    }

    fun setAiInstructions(instructions: String) {
        _aiInstructions.value = instructions
    }

    fun setModel(model: String) {
        _model.value = model
    }

    fun setThinkingEnabled(enabled: Boolean) {
        _thinkingEnabled.value = enabled
    }

    fun resetEditState() {
        _editState.value = EditUiState.Idle
    }

    fun cancelProcessing() {
        currentEditWorkId?.let { workManager.cancelWorkById(it) }
        currentRegenerateWorkId?.let { workManager.cancelWorkById(it) }
    }

    /**
     * Handle image selection from the image picker.
     * Copies the image to local storage and updates the recipe.
     */
    fun onImageSelected(contentUri: Uri) {
        viewModelScope.launch {
            try {
                val localUri = imageDownloadService.saveImageFromContentUri(contentUri)
                if (localUri != null) {
                    _imageUrl.value = localUri
                    imageChanged = true
                    recipeRepository.setImageUrl(recipeId, localUri)
                } else {
                    Log.w(TAG, "Failed to save selected image")
                    _editState.value = EditUiState.Error("Failed to save selected image")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save selected image", e)
                _editState.value = EditUiState.Error("Failed to save image: ${e.message}")
            }
        }
    }

    /**
     * Remove the recipe image.
     */
    fun removeImage() {
        viewModelScope.launch {
            try {
                val currentImageUrl = _imageUrl.value
                _imageUrl.value = null
                imageChanged = true
                recipeRepository.setImageUrl(recipeId, null)
                // Clean up the old image file if it was a local file
                if (currentImageUrl != null) {
                    imageDownloadService.deleteLocalImage(currentImageUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove image", e)
                _editState.value = EditUiState.Error("Failed to remove image: ${e.message}")
            }
        }
    }

    /**
     * Save all changes. Only triggers the AI cycle if the recipe body text was actually modified.
     * Title, URL, and image changes are saved directly without AI.
     */
    fun saveEdits() {
        val markdownChanged = _markdownText.value != originalMarkdownText
        val hasAiInstructions = _aiInstructions.value.isNotBlank()
        val titleChanged = _title.value != originalTitle
        val urlChanged = _sourceUrl.value != originalSourceUrl

        if (!markdownChanged && !hasAiInstructions) {
            // No recipe body changes — save title/URL directly if changed.
            if (titleChanged || urlChanged) {
                viewModelScope.launch {
                    try {
                        // Use NonCancellable so the DB write completes even if
                        // the coroutine scope is cancelled when the UI navigates
                        // away after observing the Success state.
                        withContext(NonCancellable) {
                            recipeRepository.updateTitleAndUrl(
                                recipeId,
                                _title.value,
                                _sourceUrl.value
                            )
                        }
                        _editState.value = EditUiState.Success()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save title/URL", e)
                        _editState.value = EditUiState.Error("Failed to save: ${e.message}")
                    }
                }
            } else {
                _editState.value = EditUiState.Success()
            }
            return
        }

        // Markdown body changed — reconstruct full markdown with title/URL and send to AI
        val fullMarkdown = buildFullMarkdown()
        val instructions = _aiInstructions.value.takeIf { it.isNotBlank() }

        val workRequest = OneTimeWorkRequestBuilder<RecipeEditWorker>()
            .setInputData(
                RecipeEditWorker.createInputData(
                    recipeId = recipeId,
                    markdownText = fullMarkdown,
                    model = _model.value,
                    thinkingEnabled = _thinkingEnabled.value,
                    aiInstructions = instructions
                )
            )
            .addTag(RecipeEditWorker.TAG_RECIPE_EDIT)
            .build()

        currentEditWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _editState.value = EditUiState.Loading("Preparing...")
    }

    /**
     * Save the edited markdown as a new recipe (copy) instead of updating the existing one.
     */
    fun saveAsCopy() {
        val fullMarkdown = buildFullMarkdown()
        val instructions = _aiInstructions.value.takeIf { it.isNotBlank() }

        val workRequest = OneTimeWorkRequestBuilder<RecipeEditWorker>()
            .setInputData(
                RecipeEditWorker.createInputData(
                    recipeId = recipeId,
                    markdownText = fullMarkdown,
                    model = _model.value,
                    thinkingEnabled = _thinkingEnabled.value,
                    saveAsCopy = true,
                    aiInstructions = instructions
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
                    thinkingEnabled = _thinkingEnabled.value
                )
            )
            .addTag(RecipeRegenerateWorker.TAG_RECIPE_REGENERATE)
            .build()

        currentRegenerateWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _editState.value = EditUiState.Loading("Preparing...")
    }

    /**
     * Reconstruct the full markdown with title and URL prepended to the body.
     * Used when sending to the AI for re-parsing.
     */
    private fun buildFullMarkdown(): String = buildString {
        val title = _title.value
        if (title.isNotBlank()) {
            appendLine("# $title")
            appendLine()
        }
        _sourceUrl.value?.let { url ->
            if (url.isNotBlank()) {
                appendLine("*Source: [$url]($url)*")
                appendLine()
            }
        }
        append(_markdownText.value)
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            // Load settings defaults
            _model.value = settingsDataStore.editModel.first()
            _thinkingEnabled.value = settingsDataStore.thinkingEnabled.first()

            // Check for original HTML
            val html = recipeRepository.getOriginalHtml(recipeId)
            _hasOriginalHtml.value = !html.isNullOrBlank()

            // Load recipe and populate fields (only if user hasn't started editing)
            val currentRecipe = recipe.first { it != null } ?: return@launch
            if (!imageChanged) {
                _imageUrl.value = currentRecipe.imageUrl
            }
            // Populate fields from the recipe only if the user hasn't started editing.
            // Each field is guarded independently so a slow recipe load doesn't
            // overwrite user input.
            if (_title.value.isBlank()) {
                _title.value = currentRecipe.name
            }
            originalTitle = currentRecipe.name
            if (_sourceUrl.value.isNullOrBlank()) {
                _sourceUrl.value = currentRecipe.sourceUrl
            }
            originalSourceUrl = currentRecipe.sourceUrl
            if (_markdownText.value.isBlank()) {
                val markdownBody = RecipeMarkdownFormatter.formatBody(currentRecipe)
                _markdownText.value = markdownBody
                originalMarkdownText = markdownBody
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
                            val newRecipeId = workInfo.outputData.getString(RecipeEditWorker.KEY_NEW_RECIPE_ID)
                            _editState.value = EditUiState.Success(newRecipeId = newRecipeId)
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
                            _editState.value = EditUiState.Success()
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

    companion object {
        private const val TAG = "EditRecipeViewModel"
    }
}
