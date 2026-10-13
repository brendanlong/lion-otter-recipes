package com.lionotter.recipes.ui.screens.addrecipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.extractFirstUrl
import com.lionotter.recipes.imports.ImportMonitor
import com.lionotter.recipes.imports.ImportQueue
import com.lionotter.recipes.imports.ImportStage
import com.lionotter.recipes.imports.ImportState
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddRecipeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importQueue: ImportQueue,
    importMonitor: ImportMonitor,
    aiSettings: AiSettings
) : ViewModel() {

    private var startJob: Job? = null
    private var cancelRequested = false

    companion object {
        // The route's url pre-fills the field (e.g. from a share); edits are saved separately so
        // they survive rotation and process death without being overwritten by the argument.
        private const val KEY_URL_INPUT = "url_input"
        /** The import started from this screen, kept so its progress is shown again after process death. */
        private const val KEY_IMPORT_ID = "import_id"
        private const val TAG = "AddRecipeViewModel"
    }

    val url: StateFlow<String> = savedStateHandle.getStateFlow(
        KEY_URL_INPUT,
        savedStateHandle.toRoute<Route.AddRecipe>().url ?: ""
    )

    /** State before an import is being tracked: idle, validation errors, or starting. */
    private val localState = MutableStateFlow<AddRecipeUiState>(AddRecipeUiState.Idle)

    private val importId: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_IMPORT_ID, null)

    private val importState: StateFlow<ImportState?> = importId.flatMapLatest { id ->
        if (id == null) flowOf(null) else importMonitor.importState(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    private val _events = Channel<AddRecipeEvent>(Channel.BUFFERED)
    val events: Flow<AddRecipeEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<AddRecipeUiState> = combine(localState, importState) { local, import ->
        when (import) {
            null -> local
            is ImportState.Running -> AddRecipeUiState.Loading(import.stage)
            // Shown until the RecipeAdded event has moved on (see init)
            is ImportState.Succeeded -> AddRecipeUiState.Loading(ImportStage.SAVING)
            is ImportState.Failed -> when (import.error) {
                RecipeError.NoApiKey -> AddRecipeUiState.NoApiKey
                else -> AddRecipeUiState.Error(import.error.toUserMessage())
            }
            ImportState.Cancelled -> AddRecipeUiState.Idle
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AddRecipeUiState.Idle
    )

    val hasApiKey: StateFlow<Boolean> = aiSettings.anthropicApiKey
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    init {
        // A finished import is reported once, even if it finished while this screen was gone
        // (the import ID survives process death).
        viewModelScope.launch {
            importState.filterIsInstance<ImportState.Succeeded>().collect { succeeded ->
                savedStateHandle[KEY_IMPORT_ID] = null
                localState.value = AddRecipeUiState.Idle
                _events.send(AddRecipeEvent.RecipeAdded(succeeded.recipeId))
            }
        }
    }

    fun onUrlChange(url: String) {
        savedStateHandle[KEY_URL_INPUT] = url
    }

    fun importRecipe() {
        savedStateHandle[KEY_IMPORT_ID] = null
        val input = url.value.trim()
        if (input.isBlank()) {
            localState.value = AddRecipeUiState.Error(UserMessage(R.string.error_enter_url))
            return
        }

        // Accept pasted text like "Recipe title https://example.com/recipe"
        val currentUrl = extractFirstUrl(input) ?: input
        if (!currentUrl.startsWith("http://", ignoreCase = true) && !currentUrl.startsWith("https://", ignoreCase = true)) {
            localState.value = AddRecipeUiState.Error(UserMessage(R.string.error_url_scheme))
            return
        }

        localState.value = AddRecipeUiState.Loading(ImportStage.QUEUED)
        cancelRequested = false
        startJob = viewModelScope.launch {
            val started = try {
                importQueue.startUrlImport(currentUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start import of $currentUrl", e)
                localState.value = AddRecipeUiState.Error(UserMessage(R.string.error_start_import))
                return@launch
            }
            if (cancelRequested) {
                importQueue.cancelImport(started.importId)
                return@launch
            }
            // From here on the import's own state is shown; it is derived by ID, so even an
            // import that already finished is reported.
            savedStateHandle[KEY_IMPORT_ID] = started.importId
        }
    }

    fun cancelImport() {
        // If the import is still being started, cancel it as soon as its ID is known.
        if (startJob?.isActive == true) cancelRequested = true
        importId.value?.let { importQueue.cancelImport(it) }
        resetState()
    }

    fun resetState() {
        savedStateHandle[KEY_IMPORT_ID] = null
        localState.value = AddRecipeUiState.Idle
    }
}

sealed class AddRecipeUiState {
    object Idle : AddRecipeUiState()
    data class Loading(val stage: ImportStage) : AddRecipeUiState()
    data class Error(val message: UserMessage) : AddRecipeUiState()
    object NoApiKey : AddRecipeUiState()
}

sealed interface AddRecipeEvent {
    /** [recipeId] is null if the import succeeded without reporting the new recipe. */
    data class RecipeAdded(val recipeId: String?) : AddRecipeEvent
}
