package com.lionotter.recipes.ui.screens.recipedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.WriteImagePromptUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The recipe screen's "Generate image" dialog: the prompt to use (the recipe's saved prompt,
 * which the user can edit or have the AI write), and starting the image generation.
 */
@HiltViewModel
class GenerateImageViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val writeImagePrompt: WriteImagePromptUseCase,
    private val imageScheduler: RecipeImageScheduler,
    aiSettings: AiSettings
) : ViewModel() {

    companion object {
        private const val TAG = "GenerateImageViewModel"
        internal const val KEY_PROMPT_DRAFT = "image_prompt_draft"
    }

    private val recipeId: String = savedStateHandle.toRoute<Route.RecipeDetail>().recipeId

    val hasOpenAiKey: StateFlow<Boolean> = aiSettings.openAiApiKey
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue = false)

    /** The prompt being edited; null while the dialog is closed. */
    val promptDraft: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_PROMPT_DRAFT, null)

    private val _writingPrompt = MutableStateFlow(false)
    val writingPrompt: StateFlow<Boolean> = _writingPrompt.asStateFlow()

    private val _events = Channel<UserMessage>(Channel.BUFFERED)
    val events: Flow<UserMessage> = _events.receiveAsFlow()

    /** Opens the dialog with the recipe's saved prompt (empty if it has none). */
    fun open() {
        viewModelScope.launch {
            val savedPrompt = try {
                recipeRepository.getRecipeByIdOnce(recipeId)?.imagePrompt
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.w(TAG, "Couldn't read the recipe's image prompt", e)
                null
            }
            savedStateHandle[KEY_PROMPT_DRAFT] = savedPrompt.orEmpty()
        }
    }

    fun onPromptChange(prompt: String) {
        if (promptDraft.value != null) savedStateHandle[KEY_PROMPT_DRAFT] = prompt
    }

    fun dismiss() {
        savedStateHandle[KEY_PROMPT_DRAFT] = null
    }

    /** Has the AI write a prompt from the recipe, replacing the draft. */
    fun writePrompt() {
        if (!_writingPrompt.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val recipe = recipeRepository.getRecipeByIdOnce(recipeId) ?: return@launch
                when (val written = writeImagePrompt.execute(recipe)) {
                    is Outcome.Success -> onPromptChange(written.value)
                    is Outcome.Failure -> _events.send(written.error.toUserMessage())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to write an image prompt", e)
                _events.send(UserMessage(R.string.image_prompt_write_failed))
            } finally {
                _writingPrompt.value = false
            }
        }
    }

    /** Saves the draft as the recipe's prompt and generates an image from it, replacing the current one. */
    fun generate() {
        val prompt = promptDraft.value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        recipeRepository.setImagePrompt(recipeId, prompt)
        imageScheduler.scheduleImageGeneration(recipeId, prompt, replaceImage = true)
        dismiss()
        viewModelScope.launch { _events.send(UserMessage(R.string.generating_image)) }
    }
}
