package com.lionotter.recipes.ui.screens.editrecipe

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.ImageChange
import com.lionotter.recipes.domain.model.MAX_TAG_LENGTH
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.tagKey
import com.lionotter.recipes.domain.model.tagKeys
import com.lionotter.recipes.domain.model.tagUsage
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.util.RecipeMarkdownFormatter
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.ui.screens.importbrowser.BrowsedPage
import com.lionotter.recipes.ui.screens.importbrowser.isWebUrl
import com.lionotter.recipes.util.DefaultDispatcher
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import com.lionotter.recipes.worker.RecipeEditWork
import com.lionotter.recipes.worker.RecipeRegenerateWork
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.WorkStatus
import com.lionotter.recipes.worker.WorkTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

sealed class EditUiState {
    object Idle : EditUiState()
    data class Loading(@param:StringRes val progress: Int) : EditUiState()
    data class Error(val message: UserMessage) : EditUiState()
    object NoApiKey : EditUiState()
}

sealed interface RecipeLoadState {
    data object Loading : RecipeLoadState
    data object NotFound : RecipeLoadState
    data class Loaded(val recipe: Recipe) : RecipeLoadState
}

/**
 * Existing tags matching what the user typed in the tag field ([matches], best first), and
 * [newTag], the typed text, when no tag of that name exists yet (so adding it creates one).
 */
data class TagSearch(val matches: List<String>, val newTag: String?) {
    companion object {
        val Empty = TagSearch(emptyList(), null)
    }
}

sealed interface EditRecipeEvent {
    /** The changes were saved; [newRecipeId] is the copy for "save as copy", otherwise null. */
    data class Saved(val newRecipeId: String?) : EditRecipeEvent

    /** Show the recipe's source page in the in-app browser before regenerating from it. */
    data class OpenBrowser(val url: String) : EditRecipeEvent
}

@HiltViewModel
class EditRecipeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val settings: AppSettings,
    private val imageStore: ImageStore,
    private val recipeWorkScheduler: RecipeWorkScheduler,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val recipeId: String = savedStateHandle.toRoute<Route.EditRecipe>().recipeId

    val loadState: StateFlow<RecipeLoadState> = recipeRepository.getRecipeById(recipeId)
        .map { recipe -> if (recipe == null) RecipeLoadState.NotFound else RecipeLoadState.Loaded(recipe) }
        .catch { e ->
            ErrorLog.e(TAG, "Failed to load recipe $recipeId", e)
            emit(RecipeLoadState.NotFound)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RecipeLoadState.Loading
        )

    /** The recipe as last loaded, or null while loading or if it doesn't exist. */
    private val recipe: StateFlow<Recipe?> = loadState
        .map { (it as? RecipeLoadState.Loaded)?.recipe }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Text fields live in SavedStateHandle so they survive process death.
    val title: StateFlow<String> = savedStateHandle.getStateFlow(KEY_TITLE, "")
    val sourceUrl: StateFlow<String?> = savedStateHandle.getStateFlow<String?>(KEY_SOURCE_URL, null)
    val markdownText: StateFlow<String> = savedStateHandle.getStateFlow(KEY_MARKDOWN, "")
    val aiInstructions: StateFlow<String> = savedStateHandle.getStateFlow(KEY_AI_INSTRUCTIONS, "")

    /** The recipe's tags as edited here. They aren't part of [markdownText]. */
    val tags: StateFlow<List<String>> = savedStateHandle.getStateFlow(KEY_TAGS, emptyList())

    /** What the user has typed in the tag field. */
    val tagQuery: StateFlow<String> = savedStateHandle.getStateFlow(KEY_TAG_QUERY, "")

    /** All the user's tags (one spelling each), most used first, to search and to reuse their spelling. */
    private val tagVocabulary: StateFlow<List<String>> = recipeRepository.getAllRecipes()
        .map { recipes -> tagUsage(recipes.map { it.tags }).map { it.name } }
        .flowOn(defaultDispatcher)
        .catch { e ->
            // Only suggestions are lost; typed tags can still be added
            ErrorLog.e(TAG, "Failed to load the tags for suggestions", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tagSearch: StateFlow<TagSearch> = combine(tagQuery, tagVocabulary, tags, ::searchTags)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TagSearch.Empty)

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

    /** What the fields would hold for the latest version of the recipe, to detect changes. */
    private val originals: StateFlow<Originals?> = recipe
        .map { recipe ->
            recipe?.let { Originals(it.name, it.sourceUrl, RecipeMarkdownFormatter.formatBody(it), it.tags) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Originals(
        val title: String,
        val sourceUrl: String?,
        val markdown: String,
        val tags: List<String>
    )

    /** What the user has entered, to compare with [Originals]. */
    private data class Fields(
        val title: String,
        val sourceUrl: String?,
        val markdown: String,
        val tags: List<String>,
        val aiInstructions: String
    )

    private val fields: Flow<Fields> = combine(title, sourceUrl, markdownText, tags, aiInstructions, ::Fields)

    /** True if the user has made changes that would be lost by leaving. */
    val hasUnsavedChanges: StateFlow<Boolean> = combine(
        fields,
        originals,
        pendingImageChanged
    ) { fields, original, imageChanged ->
        original != null && (
            imageChanged ||
                fields.title != original.title ||
                fields.sourceUrl != original.sourceUrl ||
                fields.markdown != original.markdown ||
                fields.tags != original.tags ||
                fields.aiInstructions.isNotBlank()
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

    /** The model for this edit: the user's choice on this screen, otherwise the edit model setting. */
    val model: StateFlow<String> = combine(
        savedStateHandle.getStateFlow<String?>(KEY_MODEL, null),
        settings.editModel
    ) { chosen, setting -> chosen ?: setting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiModels.DEFAULT_EDIT_MODEL)

    /** Thinking for this edit: the user's choice on this screen, otherwise the setting. */
    val thinkingEnabled: StateFlow<Boolean> = combine(
        savedStateHandle.getStateFlow<Boolean?>(KEY_THINKING, null),
        settings.thinkingEnabled
    ) { chosen, setting -> chosen ?: setting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiModels.DEFAULT_THINKING_ENABLED)

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

    fun setTagQuery(query: String) {
        savedStateHandle[KEY_TAG_QUERY] = query.take(MAX_TAG_LENGTH)
    }

    /**
     * Adds [tag] (in the spelling the user's other recipes use, if it exists) unless the recipe
     * already has it, and clears the tag field.
     */
    fun addTag(tag: String) {
        savedStateHandle[KEY_TAG_QUERY] = ""
        val key = tagKey(tag)
        if (key.isEmpty() || key in tags.value.tagKeys()) return
        val spelling = tagVocabulary.value.firstOrNull { tagKey(it) == key } ?: tag.trim()
        savedStateHandle[KEY_TAGS] = ArrayList(tags.value + spelling)
    }

    /** Adds what the user typed in the tag field (keyboard Done). */
    fun addTypedTag() = addTag(tagQuery.value)

    fun removeTag(tag: String) {
        val key = tagKey(tag)
        savedStateHandle[KEY_TAGS] = ArrayList(tags.value.filter { tagKey(it) != key })
    }

    fun setModel(model: String) {
        savedStateHandle[KEY_MODEL] = model
    }

    fun setThinkingEnabled(enabled: Boolean) {
        savedStateHandle[KEY_THINKING] = enabled
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
                val localUri = withContext(ioDispatcher) {
                    imageStore.importPickedImage(contentUri.toString())
                }
                if (localUri != null) {
                    discardPendingImageFile()
                    savedStateHandle[KEY_PENDING_IMAGE_URI] = localUri
                    savedStateHandle[KEY_PENDING_IMAGE_CHANGED] = true
                } else {
                    ErrorLog.w(TAG, "Failed to save selected image")
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

    /** Drops a picked or removed image that hasn't been saved, e.g. because an image is being generated instead. */
    fun discardPendingImageChange() {
        discardPendingImageFile()
        setPendingImageChange(null)
    }

    /** Deletes a picked-but-unsaved local image copy, if any. */
    private fun discardPendingImageFile() {
        pendingImageUri.value?.let(::discardImageFile)
    }

    private fun discardImageFile(localUri: String) {
        appScope.launch { imageStore.discardLocalImage(localUri) }
    }

    /** The pending image change, or null if the image wasn't changed. */
    private fun pendingImageChange(): ImageChange? = when {
        !pendingImageChanged.value -> null
        else -> pendingImageUri.value?.let(ImageChange::Replace) ?: ImageChange.Remove
    }

    private fun setPendingImageChange(change: ImageChange?) {
        savedStateHandle[KEY_PENDING_IMAGE_URI] = (change as? ImageChange.Replace)?.localUri
        savedStateHandle[KEY_PENDING_IMAGE_CHANGED] = change != null
    }

    /**
     * Applies an image change to this recipe: attaches the new image (which releases the previous
     * one) or removes the image and releases it.
     */
    private suspend fun applyImageChange(change: ImageChange) {
        val previousImageUrl = recipeRepository.getRecipeByIdOnce(recipeId)?.imageUrl
        when (change) {
            is ImageChange.Replace -> recipeRepository.setImageUrl(
                recipeId,
                imageStore.attach(change.localUri, recipeId = recipeId, previousImageUrl = previousImageUrl)
            )
            ImageChange.Remove -> {
                recipeRepository.setImageUrl(recipeId, null)
                previousImageUrl?.let { imageStore.release(it, recipeId) }
            }
        }
    }

    private fun isSaveInProgress(): Boolean =
        editState.value is EditUiState.Loading || localEditState.value is EditUiState.Loading

    /**
     * Save all changes. Only triggers the AI cycle if the recipe body text was actually modified
     * or AI instructions were given. Image, title, URL and tag changes are saved directly first.
     */
    fun saveEdits() {
        if (isSaveInProgress()) return
        val original = originals.value
        val needsAiEdit = markdownText.value != original?.markdown || aiInstructions.value.isNotBlank()
        val titleChanged = title.value != original?.title
        val urlChanged = sourceUrl.value != original?.sourceUrl
        val newTitle = title.value
        val newUrl = sourceUrl.value
        val newTags = changedTags()
        val editRequest = buildEditRequest(saveAsCopy = false)
        // The save takes over the picked file: it now belongs to the recipe, not this screen
        val imageChange = pendingImageChange()
        setPendingImageChange(null)

        showLocalState(EditUiState.Loading(R.string.edit_progress_saving))
        viewModelScope.launch {
            var imageApplied = false
            val startedAiEdit = try {
                // NonCancellable so the writes complete even if the screen is left
                withContext(NonCancellable) {
                    imageChange?.let { applyImageChange(it) }
                    imageApplied = true
                    // The AI path keeps the stored source URL, so save title/URL directly either way
                    if (titleChanged || urlChanged) {
                        recipeRepository.updateTitleAndUrl(recipeId, newTitle, newUrl)
                    }
                    newTags?.let { recipeRepository.setTags(recipeId, it) }
                    if (needsAiEdit) startEditWork(editRequest)
                    needsAiEdit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to save recipe changes", e)
                if (!imageApplied) {
                    // Not applied: the picked image is this screen's again, unless the screen is
                    // gone or another image was picked meanwhile
                    if (isActive && pendingImageChange() == null) {
                        setPendingImageChange(imageChange)
                    } else {
                        (imageChange as? ImageChange.Replace)?.let { discardImageFile(it.localUri) }
                    }
                }
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
     * The original is left untouched. A pending image change goes to the copy: a picked image
     * is copied and the file handed to the edit work, which stores it for the copy. This screen
     * keeps its own file, so the image is still there if the copy fails.
     */
    fun saveAsCopy() {
        if (isSaveInProgress()) return
        showLocalState(EditUiState.Loading(R.string.edit_progress_saving))
        val imageChange = pendingImageChange()
        val editRequest = buildEditRequest(saveAsCopy = true)
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val copyImage = when (imageChange) {
                        is ImageChange.Replace -> ImageChange.Replace(
                            imageStore.copyLocalImage(imageChange.localUri)
                                ?: throw IOException("Couldn't copy the picked image")
                        )
                        else -> imageChange
                    }
                    var started = false
                    try {
                        startEditWork(editRequest, copyImage)
                        started = true
                    } finally {
                        // Without the work, nothing will use the copied file
                        if (!started && copyImage is ImageChange.Replace) {
                            imageStore.discardLocalImage(copyImage.localUri)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start save as copy", e)
                localEditState.value = EditUiState.Error(UserMessage(R.string.error_save_changes))
                return@launch
            }
            localEditState.value = EditUiState.Idle
        }
    }

    private data class EditRequest(
        val markdown: String,
        val model: String,
        val thinkingEnabled: Boolean,
        val saveAsCopy: Boolean,
        val aiInstructions: String?,
        val tags: List<String>
    )

    /** The edited tags if they differ from the recipe's, otherwise null. */
    private fun changedTags(): List<String>? = tags.value.takeIf { it != originals.value?.tags }

    /**
     * The AI sees the tags in the Markdown, but the tags shown here are saved ([EditRequest.tags]),
     * not the AI's: the tag editor is the only way an edit changes them, so the AI can't bring
     * back a tag the user removed.
     */
    private fun buildEditRequest(saveAsCopy: Boolean) = EditRequest(
        markdown = RecipeMarkdownFormatter.formatForAiEdit(
            title = title.value,
            sourceUrl = sourceUrl.value,
            tags = tags.value,
            body = markdownText.value
        ),
        model = model.value,
        thinkingEnabled = thinkingEnabled.value,
        saveAsCopy = saveAsCopy,
        aiInstructions = aiInstructions.value.takeIf { it.isNotBlank() },
        tags = tags.value
    )

    /** Enqueues the AI edit and tracks it. Its input is written to disk on the IO dispatcher. */
    private suspend fun startEditWork(request: EditRequest, copyImage: ImageChange? = null) {
        val work = withContext(ioDispatcher) {
            recipeWorkScheduler.prepareEdit(
                recipeId = recipeId,
                markdownText = request.markdown,
                model = request.model,
                thinkingEnabled = request.thinkingEnabled,
                saveAsCopy = request.saveAsCopy,
                aiInstructions = request.aiInstructions,
                copyImage = copyImage,
                tags = request.tags
            )
        }
        editWork.start(work)
    }

    /**
     * The user confirmed regenerating: show the source page in the in-app browser first
     * ([EditRecipeEvent.OpenBrowser]), or regenerate directly if it isn't a web page.
     */
    fun requestRegenerate() {
        if (isSaveInProgress()) return
        val sourceUrl = recipe.value?.sourceUrl
        if (sourceUrl != null && sourceUrl.isWebUrl()) {
            _events.trySend(EditRecipeEvent.OpenBrowser(sourceUrl))
        } else {
            regenerateFromOriginal(page = null)
        }
    }

    /**
     * Regenerate the recipe from its source: the [page] captured in the in-app browser, or
     * (without a captured page) the source URL downloaded by the worker.
     */
    fun regenerateFromOriginal(page: BrowsedPage?) {
        val pageHtmlPath = page?.htmlPath
        if (isSaveInProgress()) {
            pageHtmlPath?.let(recipeWorkScheduler::discardWorkInput)
            return
        }
        showLocalState(EditUiState.Loading(R.string.edit_progress_preparing))
        val model = model.value
        val thinkingEnabled = thinkingEnabled.value
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    regenerateWork.start(
                        recipeWorkScheduler.prepareRegenerate(recipeId, model, thinkingEnabled, pageHtmlPath)
                    )
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
     * Fills the fields from the recipe once (after recreation/process death they come from
     * SavedStateHandle), then keeps the fields the user hasn't touched in step with later
     * versions of the recipe, so a change made elsewhere isn't reverted by saving.
     */
    private fun syncFieldsWithRecipe() {
        viewModelScope.launch {
            var base: Originals? = null
            originals.filterNotNull().collect { latest ->
                val previous = base
                if (previous == null) {
                    if (savedStateHandle.get<Boolean>(KEY_FIELDS_INITIALIZED) != true) {
                        savedStateHandle[KEY_TITLE] = latest.title
                        savedStateHandle[KEY_SOURCE_URL] = latest.sourceUrl
                        savedStateHandle[KEY_MARKDOWN] = latest.markdown
                        savedStateHandle[KEY_TAGS] = ArrayList(latest.tags)
                        savedStateHandle[KEY_FIELDS_INITIALIZED] = true
                    }
                } else {
                    if (title.value == previous.title) savedStateHandle[KEY_TITLE] = latest.title
                    if (sourceUrl.value == previous.sourceUrl) savedStateHandle[KEY_SOURCE_URL] = latest.sourceUrl
                    if (markdownText.value == previous.markdown) savedStateHandle[KEY_MARKDOWN] = latest.markdown
                    if (tags.value == previous.tags) savedStateHandle[KEY_TAGS] = ArrayList(latest.tags)
                }
                base = latest
            }
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
        syncFieldsWithRecipe()
        trackFinishedWork()
    }

    override fun onCleared() {
        // Leaving without saving: drop the unsaved picked image copy. A save takes the file
        // over (see saveEdits), so whatever is still pending belongs to this screen.
        discardPendingImageFile()
    }

    companion object {
        private const val TAG = "EditRecipeViewModel"
        private const val KEY_TITLE = "edit_title"
        private const val KEY_SOURCE_URL = "edit_source_url"
        private const val KEY_MARKDOWN = "edit_markdown"
        private const val KEY_AI_INSTRUCTIONS = "edit_ai_instructions"
        private const val KEY_TAGS = "edit_tags"
        private const val KEY_TAG_QUERY = "edit_tag_query"
        private const val MAX_TAG_MATCHES = 8

        /**
         * The [vocabulary] tags containing [query] that the recipe doesn't have yet ([current]),
         * exact and prefix matches first, and the query as a new tag if no tag has its name.
         */
        internal fun searchTags(query: String, vocabulary: List<String>, current: List<String>): TagSearch {
            val typed = query.trim()
            if (typed.isEmpty()) return TagSearch.Empty
            val key = tagKey(typed)
            val currentKeys = current.tagKeys()
            val matches = vocabulary
                .filter { it.contains(typed, ignoreCase = true) && tagKey(it) !in currentKeys }
                .sortedBy {
                    when {
                        tagKey(it) == key -> 0
                        tagKey(it).startsWith(key) -> 1
                        else -> 2
                    }
                }
                .take(MAX_TAG_MATCHES)
            val exists = key in currentKeys || vocabulary.any { tagKey(it) == key }
            return TagSearch(matches, typed.takeUnless { exists })
        }
        private const val KEY_FIELDS_INITIALIZED = "edit_fields_initialized"
        private const val KEY_PENDING_IMAGE_CHANGED = "edit_pending_image_changed"
        private const val KEY_PENDING_IMAGE_URI = "edit_pending_image_uri"
        private const val KEY_EDIT_WORK_ID = "edit_work_id"
        private const val KEY_REGENERATE_WORK_ID = "edit_regenerate_work_id"
        private const val KEY_MODEL = "edit_model"
        private const val KEY_THINKING = "edit_thinking"
    }
}
