package com.lionotter.recipes.ui.screens.editrecipe

import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.worker.RecipeEditWork
import com.lionotter.recipes.worker.RecipeRegenerateWork
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.WorkStatus
import com.lionotter.recipes.worker.WorkTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

sealed class EditUiState {
    object Idle : EditUiState()
    data class Loading(@param:StringRes val progress: Int) : EditUiState()
    data class Error(val message: UserMessage) : EditUiState()
    object NoApiKey : EditUiState()
}

sealed interface EditRecipeEvent {
    /** The changes were saved; [newRecipeId] is the copy for "save as copy", otherwise null. */
    data class Saved(val newRecipeId: String?) : EditRecipeEvent
}

@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: IRecipeRepository,
    private val settingsDataStore: SettingsDataStore,
    private val imageDownloadService: ImageDownloadService,
    private val recipeWorkScheduler: RecipeWorkScheduler,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private val recipeId: String = savedStateHandle.toRoute<Route.EditRecipe>().recipeId

    /** True once the recipe flow has emitted at least once (so null means "not found"). */
    private val _recipeLoaded = MutableStateFlow(false)
    val recipeLoaded: StateFlow<Boolean> = _recipeLoaded.asStateFlow()

    val recipe: StateFlow<Recipe?> = recipeRepository.getRecipeById(recipeId)
        .onEach { _recipeLoaded.value = true }
        .catch { e ->
            ErrorLog.e(TAG, "Failed to load recipe $recipeId", e)
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

    /** State of work done in this ViewModel (direct saves, starting the AI work, image errors). */
    private val localEditState = MutableStateFlow<EditUiState>(EditUiState.Idle)

    // The AI edit / regeneration this screen started, kept across process death
    private val editWork = WorkTracker(savedStateHandle, KEY_EDIT_WORK_ID, viewModelScope, recipeWorkScheduler::observeEdit)
    private val regenerateWork =
        WorkTracker(savedStateHandle, KEY_REGENERATE_WORK_ID, viewModelScope, recipeWorkScheduler::observeRegenerate)
    private val editStatus = editWork.status.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val regenerateStatus = regenerateWork.status.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _events = Channel<EditRecipeEvent>(Channel.BUFFERED)
    val events: Flow<EditRecipeEvent> = _events.receiveAsFlow()

    /** The tracked work's state once there is one, otherwise the local state. */
    val editState: StateFlow<EditUiState> = combine(
        localEditState,
        editStatus,
        regenerateStatus
    ) { local, edit, regenerate ->
        edit?.let(::editWorkState) ?: regenerate?.let(::regenerateWorkState) ?: local
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = EditUiState.Idle
    )

    private val _model = MutableStateFlow(AiModels.DEFAULT_EDIT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(AiModels.DEFAULT_THINKING_ENABLED)
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

    /** Show [state], dropping any finished work this screen was showing. */
    private fun showLocalState(state: EditUiState) {
        editWork.clear()
        regenerateWork.clear()
        localEditState.value = state
    }

    fun cancelProcessing() {
        editWork.workId?.let(recipeWorkScheduler::cancel)
        regenerateWork.workId?.let(recipeWorkScheduler::cancel)
        localEditState.value = EditUiState.Idle
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
                    showLocalState(EditUiState.Error(UserMessage(R.string.error_image_unusable)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to save selected image", e)
                showLocalState(EditUiState.Error(UserMessage(R.string.error_image_unusable)))
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
        val uri = pendingImageUri.value ?: return
        appScope.launch { imageDownloadService.deleteLocalImage(uri) }
    }

    /**
     * True while a save owns the pending image file (it is being stored, or is waiting to be
     * applied to a copy), so [onCleared] must not delete it.
     */
    @Volatile
    private var pendingImageOwnedBySave = false

    @Volatile
    private var isCleared = false

    /**
     * Applies a pending image change to the recipe: uploads the new image (cleaning up the
     * previous one) or removes the image. Must be called from a NonCancellable context.
     */
    private suspend fun applyPendingImageChange() {
        if (!pendingImageChanged.value) return
        pendingImageOwnedBySave = true
        try {
            applyImageChange(recipeId, pendingImageUri.value)
        } finally {
            pendingImageOwnedBySave = false
        }
        // The local file now belongs to the recipe; don't delete it on clear
        clearPendingImage()
    }

    private suspend fun applyImageChange(targetRecipeId: String, newLocalUri: String?) {
        val previousImageUrl = recipeRepository.getRecipeByIdOnce(targetRecipeId)?.imageUrl
        val storedUrl = if (newLocalUri != null) {
            imageDownloadService.storeImage(newLocalUri, previousImageUrl, recipeId = targetRecipeId)
        } else {
            previousImageUrl?.let { imageDownloadService.cleanupImage(it, recipeId = targetRecipeId) }
            null
        }
        recipeRepository.setImageUrl(targetRecipeId, storedUrl)
    }

    private fun clearPendingImage() {
        savedStateHandle[KEY_PENDING_IMAGE_URI] = null
        savedStateHandle[KEY_PENDING_IMAGE_CHANGED] = false
    }

    private fun isSaveInProgress(): Boolean =
        editState.value is EditUiState.Loading || localEditState.value is EditUiState.Loading

    /**
     * Save all changes. Only triggers the AI cycle if the recipe body text was actually modified
     * or AI instructions were given. Image, title, and URL changes are saved directly first.
     */
    fun saveEdits() {
        if (isSaveInProgress()) return
        val original = originals.value
        val needsAiEdit = markdownText.value != original?.markdown || aiInstructions.value.isNotBlank()
        val titleChanged = title.value != original?.title
        val urlChanged = sourceUrl.value != original?.sourceUrl
        val newTitle = title.value
        val newUrl = sourceUrl.value
        val editRequest = buildEditRequest(saveAsCopy = false)

        showLocalState(EditUiState.Loading(R.string.edit_progress_saving))
        viewModelScope.launch {
            val startedAiEdit = try {
                // NonCancellable so the writes complete even if the screen is left
                withContext(NonCancellable) {
                    applyPendingImageChange()
                    // The AI path keeps the stored source URL, so save title/URL directly either way
                    if (titleChanged || urlChanged) {
                        recipeRepository.updateTitleAndUrl(recipeId, newTitle, newUrl)
                    }
                    if (needsAiEdit) startEditWork(editRequest)
                    needsAiEdit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to save recipe changes", e)
                localEditState.value = EditUiState.Error(UserMessage(R.string.error_save_changes))
                return@launch
            }

            // Otherwise the tracked edit work's state is shown from here on
            localEditState.value = EditUiState.Idle
            if (!startedAiEdit) _events.send(EditRecipeEvent.Saved(newRecipeId = null))
        }
    }

    /**
     * Save the edited markdown as a new recipe (copy) instead of updating the existing one.
     * The original is left untouched; a pending image change is applied to the copy once
     * the edit worker has created it.
     */
    fun saveAsCopy() {
        if (isSaveInProgress()) return
        showLocalState(EditUiState.Loading(R.string.edit_progress_saving))
        val hasImageChange = pendingImageChanged.value
        val newLocalUri = pendingImageUri.value
        val editRequest = buildEditRequest(saveAsCopy = true)
        if (hasImageChange) pendingImageOwnedBySave = true
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val workId = startEditWork(editRequest)
                    if (hasImageChange) {
                        appScope.launch { applyImageToCopyWhenDone(workId, newLocalUri) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start save as copy", e)
                pendingImageOwnedBySave = false
                localEditState.value = EditUiState.Error(UserMessage(R.string.error_save_changes))
                return@launch
            }
            localEditState.value = EditUiState.Idle
        }
    }

    /**
     * Waits for the save-as-copy worker, then applies the pending image change to the copy
     * it created. Runs in the app scope so it completes even if this screen is left.
     */
    private suspend fun applyImageToCopyWhenDone(workId: UUID, newLocalUri: String?) {
        val copyId = try {
            val status = recipeWorkScheduler.observeEdit(workId)
                .first { it !is WorkStatus.Queued && it !is WorkStatus.Running }
            ((status as? WorkStatus.Finished)?.outcome as? RecipeEditWork.Outcome.Success)?.newRecipeId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to observe save-as-copy work", e)
            null
        }
        if (copyId == null) {
            // The copy wasn't created; the image stays pending on this screen (if still open)
            pendingImageOwnedBySave = false
            if (isCleared && newLocalUri != null) imageDownloadService.deleteLocalImage(newLocalUri)
            return
        }
        try {
            withContext(NonCancellable) { applyImageChange(copyId, newLocalUri) }
            withContext(Dispatchers.Main) { clearPendingImage() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to apply image to recipe copy $copyId", e)
            localEditState.value = EditUiState.Error(UserMessage(R.string.error_copy_image))
        }
    }

    private data class EditRequest(
        val markdown: String,
        val model: String,
        val thinkingEnabled: Boolean,
        val saveAsCopy: Boolean,
        val aiInstructions: String?
    )

    private fun buildEditRequest(saveAsCopy: Boolean) = EditRequest(
        markdown = buildFullMarkdown(),
        model = _model.value,
        thinkingEnabled = _thinkingEnabled.value,
        saveAsCopy = saveAsCopy,
        aiInstructions = aiInstructions.value.takeIf { it.isNotBlank() }
    )

    /** Enqueues the AI edit and tracks it. Its input is written to disk on the IO dispatcher. */
    private suspend fun startEditWork(request: EditRequest): UUID {
        val work = withContext(Dispatchers.IO) {
            recipeWorkScheduler.prepareEdit(
                recipeId = recipeId,
                markdownText = request.markdown,
                model = request.model,
                thinkingEnabled = request.thinkingEnabled,
                saveAsCopy = request.saveAsCopy,
                aiInstructions = request.aiInstructions
            )
        }
        editWork.start(work)
        return work.id
    }

    /**
     * Regenerate the recipe from the original source HTML/URL.
     */
    fun regenerateFromOriginal() {
        if (isSaveInProgress()) return
        showLocalState(EditUiState.Loading(R.string.edit_progress_preparing))
        val model = _model.value
        val thinkingEnabled = _thinkingEnabled.value
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    regenerateWork.start(recipeWorkScheduler.prepareRegenerate(recipeId, model, thinkingEnabled))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start regeneration", e)
                localEditState.value = EditUiState.Error(UserMessage(R.string.error_start_regeneration))
                return@launch
            }
            localEditState.value = EditUiState.Idle
        }
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

    private fun editWorkState(status: WorkStatus<RecipeEditWork.Progress, RecipeEditWork.Outcome>): EditUiState? =
        when (status) {
            WorkStatus.Missing, WorkStatus.Queued -> EditUiState.Loading(R.string.edit_progress_preparing)
            is WorkStatus.Running -> EditUiState.Loading(
                when (status.progress) {
                    null -> R.string.starting
                    is RecipeEditWork.Progress.Parsing -> R.string.edit_progress_ai_processing
                    RecipeEditWork.Progress.Saving -> R.string.saving_recipe
                }
            )
            is WorkStatus.Finished -> when (val outcome = status.outcome) {
                // Until the Saved event has been sent (see trackFinishedWork)
                is RecipeEditWork.Outcome.Success -> EditUiState.Loading(R.string.saving_recipe)
                is RecipeEditWork.Outcome.Failed -> failureState(outcome.error)
            }
            WorkStatus.Cancelled -> null
        }

    private fun regenerateWorkState(
        status: WorkStatus<RecipeRegenerateWork.Progress, RecipeRegenerateWork.Outcome>
    ): EditUiState? = when (status) {
        WorkStatus.Missing, WorkStatus.Queued -> EditUiState.Loading(R.string.edit_progress_preparing)
        is WorkStatus.Running -> EditUiState.Loading(
            when (status.progress) {
                null -> R.string.starting
                RecipeRegenerateWork.Progress.FetchingPage -> R.string.fetching_recipe_page
                is RecipeRegenerateWork.Progress.Parsing -> R.string.edit_progress_ai_reanalyzing
                RecipeRegenerateWork.Progress.Saving -> R.string.saving_recipe
            }
        )
        is WorkStatus.Finished -> when (val outcome = status.outcome) {
            is RecipeRegenerateWork.Outcome.Success -> EditUiState.Loading(R.string.saving_recipe)
            is RecipeRegenerateWork.Outcome.Failed -> failureState(outcome.error)
        }
        WorkStatus.Cancelled -> null
    }

    private fun failureState(error: RecipeError): EditUiState =
        if (error == RecipeError.NoApiKey) EditUiState.NoApiKey else EditUiState.Error(error.toUserMessage())

    /**
     * Reports successfully finished work once as [EditRecipeEvent.Saved] and stops tracking it,
     * including work that finished while this screen was gone.
     */
    private fun trackFinishedWork() {
        viewModelScope.launch {
            editStatus.collect { status ->
                val outcome = (status as? WorkStatus.Finished)?.outcome as? RecipeEditWork.Outcome.Success
                    ?: return@collect
                editWork.clear()
                _events.send(EditRecipeEvent.Saved(outcome.newRecipeId))
            }
        }
        viewModelScope.launch {
            regenerateStatus.collect { status ->
                if ((status as? WorkStatus.Finished)?.outcome !is RecipeRegenerateWork.Outcome.Success) return@collect
                regenerateWork.clear()
                _events.send(EditRecipeEvent.Saved(newRecipeId = null))
            }
        }
    }

    init {
        loadInitialData()
        trackFinishedWork()
    }

    override fun onCleared() {
        isCleared = true
        // Leaving without saving: drop the unsaved picked image copy. A save in progress
        // owns the file and cleans it up itself.
        if (pendingImageChanged.value && !pendingImageOwnedBySave) {
            discardPendingImageFile()
        }
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
        private const val KEY_EDIT_WORK_ID = "edit_work_id"
        private const val KEY_REGENERATE_WORK_ID = "edit_regenerate_work_id"
    }
}
