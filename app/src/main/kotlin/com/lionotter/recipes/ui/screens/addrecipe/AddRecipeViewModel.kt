package com.lionotter.recipes.ui.screens.addrecipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.extractFirstUrl
import com.lionotter.recipes.imports.ImportFailure
import com.lionotter.recipes.imports.ImportMonitor
import com.lionotter.recipes.imports.ImportQueue
import com.lionotter.recipes.imports.ImportStage
import com.lionotter.recipes.imports.ImportState
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddRecipeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importQueue: ImportQueue,
    importMonitor: ImportMonitor,
    settingsDataStore: SettingsDataStore
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

    private val importState = importId.flatMapLatest { id ->
        if (id == null) flowOf(null) else importMonitor.importState(id)
    }

    val uiState: StateFlow<AddRecipeUiState> = combine(localState, importState) { local, import ->
        when (import) {
            null -> local
            is ImportState.Running -> AddRecipeUiState.Loading(import.stage)
            is ImportState.Succeeded -> AddRecipeUiState.Success(import.recipeId)
            is ImportState.Failed -> when (import.reason) {
                ImportFailure.NO_API_KEY -> AddRecipeUiState.NoApiKey
                ImportFailure.ERROR -> AddRecipeUiState.Error(import.message)
            }
            ImportState.Cancelled -> AddRecipeUiState.Idle
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AddRecipeUiState.Idle
    )

    val hasApiKey: StateFlow<Boolean> = settingsDataStore.anthropicApiKey
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun onUrlChange(url: String) {
        savedStateHandle[KEY_URL_INPUT] = url
    }

    fun importRecipe() {
        savedStateHandle[KEY_IMPORT_ID] = null
        val input = url.value.trim()
        if (input.isBlank()) {
            localState.value = AddRecipeUiState.Error("Please enter a URL")
            return
        }

        // Accept pasted text like "Recipe title https://example.com/recipe"
        val currentUrl = extractFirstUrl(input) ?: input
        if (!currentUrl.startsWith("http://", ignoreCase = true) && !currentUrl.startsWith("https://", ignoreCase = true)) {
            localState.value = AddRecipeUiState.Error("URL must start with http:// or https://")
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
                localState.value = AddRecipeUiState.Error("Couldn't start the import: ${e.message ?: "unknown error"}")
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
    /** [recipeId] is null if the import succeeded without reporting the new recipe. */
    data class Success(val recipeId: String?) : AddRecipeUiState()
    data class Error(val message: String) : AddRecipeUiState()
    object NoApiKey : AddRecipeUiState()
}
