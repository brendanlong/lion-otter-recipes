package com.lionotter.recipes.ui.screens.editrecipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.ModelRole
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.RecipeImageScheduler
import com.lionotter.recipes.domain.port.model
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.GenerateRecipeImageUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The edit screen's "Generate image" dialog: the prompt to use (the recipe's saved prompt, which
 * the user can edit), and starting the image generation.
 */
@HiltViewModel
class GenerateImageViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val imageScheduler: RecipeImageScheduler,
    aiSettings: AiSettings
) : ViewModel() {

    companion object {
        private const val TAG = "GenerateImageViewModel"
        internal const val KEY_PROMPT_DRAFT = "image_prompt_draft"
    }

    private val recipeId: String = savedStateHandle.toRoute<Route.EditRecipe>().recipeId

    val hasImageModel: StateFlow<Boolean> = aiSettings.model(ModelRole.IMAGE)
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue = false)

    /** The prompt being edited; null while the dialog is closed. */
    val promptDraft: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_PROMPT_DRAFT, null)

    private val _events = Channel<UserMessage>(Channel.BUFFERED)
    val events: Flow<UserMessage> = _events.receiveAsFlow()

    /** Opens the dialog with [recipe]'s saved prompt, or a simple one for a recipe without. */
    fun open(recipe: Recipe) {
        savedStateHandle[KEY_PROMPT_DRAFT] = recipe.imagePrompt ?: GenerateRecipeImageUseCase.fallbackPrompt(recipe)
    }

    fun onPromptChange(prompt: String) {
        if (promptDraft.value != null) savedStateHandle[KEY_PROMPT_DRAFT] = prompt.take(ResourceLimits.MAX_IMAGE_PROMPT_CHARS)
    }

    fun dismiss() {
        savedStateHandle[KEY_PROMPT_DRAFT] = null
    }

    /** Saves the draft as the recipe's prompt and generates an image from it, replacing the current one. */
    fun generate() {
        val prompt = promptDraft.value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        dismiss()
        recipeRepository.setImagePrompt(recipeId, prompt)
        viewModelScope.launch {
            val message = try {
                withContext(NonCancellable) {
                    imageScheduler.scheduleImageGeneration(recipeId, prompt, replaceImage = true)
                }
                UserMessage(R.string.generating_image)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to schedule image generation", e)
                UserMessage(R.string.generate_image_failed)
            }
            _events.send(message)
        }
    }
}
