package com.lionotter.recipes.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.usecase.GenerateMissingImagesUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings' "Generate missing images": finds the recipes without one and, once confirmed, queues their images. */
@HiltViewModel
class MissingImagesViewModel @Inject constructor(
    private val generateMissingImages: GenerateMissingImagesUseCase
) : ViewModel() {

    private companion object {
        const val TAG = "MissingImagesViewModel"
    }

    /** The recipes to confirm generating images for; null when not asking. */
    private val _pendingConfirmation = MutableStateFlow<List<String>?>(null)
    val pendingConfirmation: StateFlow<List<String>?> = _pendingConfirmation.asStateFlow()

    private val _events = Channel<UserMessage>(Channel.BUFFERED)
    val events: Flow<UserMessage> = _events.receiveAsFlow()

    fun findMissingImages() {
        viewModelScope.launch {
            val recipeIds = try {
                generateMissingImages.recipesWithoutImages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Couldn't find the recipes without images", e)
                _events.send(UserMessage(R.string.missing_images_read_failed))
                return@launch
            }
            if (recipeIds.isEmpty()) {
                _events.send(UserMessage(R.string.no_missing_images))
            } else {
                _pendingConfirmation.value = recipeIds
            }
        }
    }

    fun confirm() {
        val recipeIds = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        generateMissingImages.execute(recipeIds)
        viewModelScope.launch {
            _events.send(UserMessage.Plural(R.plurals.generating_missing_images, recipeIds.size))
        }
    }

    fun dismiss() {
        _pendingConfirmation.value = null
    }
}
