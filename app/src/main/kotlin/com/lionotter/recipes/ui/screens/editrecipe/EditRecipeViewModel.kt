package com.lionotter.recipes.ui.screens.editrecipe

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.observeWorkByTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: IRecipeRepository,
    private val settingsDataStore: SettingsDataStore,
    private val workManager: WorkManager,
    private val imageDownloadService: ImageDownloadService,
    private val recipeWorkScheduler: RecipeWorkScheduler
) : ViewModel() {

    private val recipeId: String = savedStateHandle.get<String>("recipeId")
        ?: throw IllegalArgumentException("EditRecipeViewModel requires a 'recipeId' argument in SavedStateHandle")

    /** True once the recipe flow has emitted at least once (so null means "not found"). */
    private val _recipeLoaded = MutableStateFlow(false)
    val recipeLoaded: StateFlow<Boolean> = _recipeLoaded.asStateFlow()

    val recipe: StateFlow<Recipe?> = recipeRepository.getRecipeById(recipeId)
        .onEach { _recipeLoaded.value = true }
        .catch { e ->
            Log.e(TAG, "Failed to load recipe $recipeId", e)
            _recipeLoaded.value = true
            emit(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    // Text fields live in SavedStateHandle so they survive process death.
    val title: StateFlow<String> = savedStateHandle.getStateFlow(KEY_TITLE, "")
    val sourceUrl: StateFlow<String?> = savedStateHandle.getStateFlow<String?>(KEY_SOURCE_URL, null)
    val markdownText: StateFlow<String> = savedStateHandle.getStateFlow(KEY_MARKDOWN, "")
    val aiInstructions: StateFlow<String> = savedStateHandle.getStateFlow(KEY_AI_INSTRUCTIONS, "")

    /**
     * An image change (pick or remove) is held as pending until the user saves, so that
     * cancelling the edit leaves the recipe untouched. A pending pick is a local file:// copy.
     */
    private val pendingImageChanged: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_PENDING_IMAGE_CHANGED, false)
    private val pendingImageUri: StateFlow<String?> =
        savedStateHandle.getStateFlow<String?>(KEY_PENDING_IMAGE_URI, null)

    /** The image to show: the pending change if any, otherwise the recipe's current image. */
    val imageUrl: StateFlow<String?> = combine(
        recipe,
        pendingImageChanged,
        pendingImageUri
    ) { recipe, changed, pendingUri ->
        if (changed) pendingUri else recipe?.imageUrl
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    /** The original values loaded from the recipe, used to detect changes. */
    private val originals = MutableStateFlow<Originals?>(null)

    private data class Originals(val title: String, val sourceUrl: String?, val markdown: String)

    /** True if the user has made changes that would be lost by leaving. */
    val hasUnsavedChanges: StateFlow<Boolean> = combine(
        title,
        sourceUrl,
        markdownText,
        aiInstructions,
        combine(originals, pendingImageChanged) { original, imageChanged -> original to imageChanged }
    ) { title, url, markdown, ai, (original, imageChanged) ->
        original != null && (
            imageChanged ||
                title != original.title ||
                url != original.sourceUrl ||
                markdown != original.markdown ||
                ai.isNotBlank()
            )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    private val _editState = MutableStateFlow<EditUiState>(EditUiState.Idle)
    val editState: StateFlow<EditUiState> = _editState.asStateFlow()

    private val _model = MutableStateFlow(AnthropicService.DEFAULT_EDIT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(SettingsDataStore.DEFAULT_THINKING_ENABLED)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    /**
     * Whether regeneration from original source is available.
     * True if the recipe has a source URL to re-fetch from.
     */
    val canRegenerate: StateFlow<Boolean> = recipe
        .map { recipe -> !recipe?.sourceUrl.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private var currentEditWorkId: UUID? = null
    private var currentRegenerateWorkId: UUID? = null

    fun setTitle(title: String) {
        savedStateHandle[KEY_TITLE] = title
    }

    fun setSourceUrl(url: String?) {
        savedStateHandle[KEY_SOURCE_URL] = url
    }

    fun setMarkdownText(text: String) {
        savedStateHandle[KEY_MARKDOWN] = text
    }

    fun setAiInstructions(instructions: String) {
        savedStateHandle[KEY_AI_INSTRUCTIONS] = instructions
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
     * Handle image selection from the image picker. The image is copied to local storage
     * and shown immediately, but only applied to the recipe when the user saves.
     */
    fun onImageSelected(contentUri: Uri) {
        viewModelScope.launch {
            try {
                val localUri = withContext(Dispatchers.IO) {
                    imageDownloadService.saveImageFromContentUri(contentUri)
                }
                if (localUri != null) {
                    discardPendingImageFile()
                    savedStateHandle[KEY_PENDING_IMAGE_URI] = localUri
                    savedStateHandle[KEY_PENDING_IMAGE_CHANGED] = true
                } else {
                    Log.w(TAG, "Failed to save selected image")
                    _editState.value = EditUiState.Error("Failed to save selected image")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save selected image", e)
                _editState.value = EditUiState.Error("Failed to save image: ${e.message}")
            }
        }
    }

    /** Remove the recipe image (applied when the user saves). */
    fun removeImage() {
        discardPendingImageFile()
        savedStateHandle[KEY_PENDING_IMAGE_URI] = null
        savedStateHandle[KEY_PENDING_IMAGE_CHANGED] = true
    }

    /** Deletes a picked-but-unsaved local image copy, if any. */
    private fun discardPendingImageFile() {
        pendingImageUri.value?.let { imageDownloadService.deleteLocalImage(it) }
    }

    /**
     * Applies a pending image change to the recipe: uploads the new image (cleaning up the
     * previous one) or removes the image. Must be called from a NonCancellable context.
     */
    private suspend fun applyPendingImageChange() {
        if (!pendingImageChanged.value) return
        val newLocalUri = pendingImageUri.value
        val previousImageUrl = recipeRepository.getRecipeByIdOnce(recipeId)?.imageUrl
        val storedUrl = if (newLocalUri != null) {
            imageDownloadService.storeImage(newLocalUri, previousImageUrl)
        } else {
            previousImageUrl?.let { imageDownloadService.cleanupImage(it) }
            null
        }
        recipeRepository.setImageUrl(recipeId, storedUrl)
        // The local file now belongs to the recipe; don't delete it on clear
        savedStateHandle[KEY_PENDING_IMAGE_URI] = null
        savedStateHandle[KEY_PENDING_IMAGE_CHANGED] = false
    }

    private fun isSaveInProgress(): Boolean = _editState.value is EditUiState.Loading

    /**
     * Save all changes. Only triggers the AI cycle if the recipe body text was actually modified
     * or AI instructions were given. Image, title, and URL changes are saved directly first.
     */
    fun saveEdits() {
        if (isSaveInProgress()) return
        val original = originals.value
        val markdownChanged = markdownText.value != original?.markdown
        val hasAiInstructions = aiInstructions.value.isNotBlank()
        val titleChanged = title.value != original?.title
        val urlChanged = sourceUrl.value != original?.sourceUrl
        val newTitle = title.value
        val newUrl = sourceUrl.value

        _editState.value = EditUiState.Loading("Saving...")
        viewModelScope.launch {
            try {
                // NonCancellable so the writes complete even if the screen is left
                withContext(NonCancellable) {
                    applyPendingImageChange()
                    // The AI path keeps the stored source URL, so save title/URL directly either way
                    if (titleChanged || urlChanged) {
                        recipeRepository.updateTitleAndUrl(recipeId, newTitle, newUrl)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save recipe changes", e)
                _editState.value = EditUiState.Error("Failed to save: ${e.message}")
                return@launch
            }

            if (!markdownChanged && !hasAiInstructions) {
                _editState.value = EditUiState.Success()
            } else {
                enqueueEdit(saveAsCopy = false)
            }
        }
    }

    /**
     * Save the edited markdown as a new recipe (copy) instead of updating the existing one.
     * A pending image change is applied to the original first, and the copy uses it too.
     */
    fun saveAsCopy() {
        if (isSaveInProgress()) return
        _editState.value = EditUiState.Loading("Saving...")
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { applyPendingImageChange() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image before copying", e)
                _editState.value = EditUiState.Error("Failed to save image: ${e.message}")
                return@launch
            }
            enqueueEdit(saveAsCopy = true)
        }
    }

    private fun enqueueEdit(saveAsCopy: Boolean) {
        currentEditWorkId = recipeWorkScheduler.enqueueEdit(
            recipeId = recipeId,
            markdownText = buildFullMarkdown(),
            model = _model.value,
            thinkingEnabled = _thinkingEnabled.value,
            saveAsCopy = saveAsCopy,
            aiInstructions = aiInstructions.value.takeIf { it.isNotBlank() }
        )
        _editState.value = EditUiState.Loading("Preparing...")
    }

    /**
     * Regenerate the recipe from the original source HTML/URL.
     */
    fun regenerateFromOriginal() {
        currentRegenerateWorkId = recipeWorkScheduler.enqueueRegenerate(
            recipeId = recipeId,
            model = _model.value,
            thinkingEnabled = _thinkingEnabled.value
        )
        _editState.value = EditUiState.Loading("Preparing...")
    }

    /**
     * Reconstruct the full markdown with title and URL prepended to the body.
     * Used when sending to the AI for re-parsing.
     */
    private fun buildFullMarkdown(): String = buildString {
        val title = title.value
        if (title.isNotBlank()) {
            appendLine("# $title")
            appendLine()
        }
        sourceUrl.value?.let { url ->
            if (url.isNotBlank()) {
                appendLine("*Source: [$url]($url)*")
                appendLine()
            }
        }
        append(markdownText.value)
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                // Load settings defaults
                _model.value = settingsDataStore.editModel.first()
                _thinkingEnabled.value = settingsDataStore.thinkingEnabled.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load edit model settings; using defaults", e)
            }

            val currentRecipe = recipe.first { it != null } ?: return@launch
            val markdownBody = RecipeMarkdownFormatter.formatBody(currentRecipe)
            // Populate fields once; after recreation/process death they come from SavedStateHandle
            if (savedStateHandle.get<Boolean>(KEY_FIELDS_INITIALIZED) != true) {
                savedStateHandle[KEY_TITLE] = currentRecipe.name
                savedStateHandle[KEY_SOURCE_URL] = currentRecipe.sourceUrl
                savedStateHandle[KEY_MARKDOWN] = markdownBody
                savedStateHandle[KEY_FIELDS_INITIALIZED] = true
            }
            originals.value = Originals(currentRecipe.name, currentRecipe.sourceUrl, markdownBody)
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

    override fun onCleared() {
        // Leaving without saving: drop the unsaved picked image copy
        if (pendingImageChanged.value) {
            discardPendingImageFile()
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "EditRecipeViewModel"
        private const val KEY_TITLE = "edit_title"
        private const val KEY_SOURCE_URL = "edit_source_url"
        private const val KEY_MARKDOWN = "edit_markdown"
        private const val KEY_AI_INSTRUCTIONS = "edit_ai_instructions"
        private const val KEY_FIELDS_INITIALIZED = "edit_fields_initialized"
        private const val KEY_PENDING_IMAGE_CHANGED = "edit_pending_image_changed"
        private const val KEY_PENDING_IMAGE_URI = "edit_pending_image_uri"
    }
}
