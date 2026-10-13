package com.lionotter.recipes.ui.screens.addrecipe

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.RecipeErrorSource
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.ModelRole
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.model
import com.lionotter.recipes.extractFirstUrl
import com.lionotter.recipes.imports.CameraPhotoFiles
import com.lionotter.recipes.imports.ImportFileInspector
import com.lionotter.recipes.imports.ImportMonitor
import com.lionotter.recipes.imports.ImportQueue
import com.lionotter.recipes.imports.ImportStage
import com.lionotter.recipes.imports.ImportState
import com.lionotter.recipes.imports.RecipeFile
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.ui.screens.importbrowser.BrowsedPage
import com.lionotter.recipes.ui.screens.importselection.ImportType
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddRecipeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importQueue: ImportQueue,
    importMonitor: ImportMonitor,
    aiSettings: AiSettings,
    private val importFileInspector: ImportFileInspector,
    private val cameraPhotos: CameraPhotoFiles
) : ViewModel() {

    /** An import being started; [cancelled] once the user cancels it before its ID is known. */
    private class PendingStart {
        var cancelled = false
    }

    private var pendingStart: PendingStart? = null

    /** Whether the camera app was asked for a photo and hasn't answered yet (ignores double taps). */
    private var cameraOpen = false

    companion object {
        // The route's url pre-fills the field (e.g. from a share); edits are saved separately so
        // they survive rotation and process death without being overwritten by the argument.
        private const val KEY_URL_INPUT = "url_input"
        /** The import started from this screen, kept so its progress is shown again after process death. */
        private const val KEY_IMPORT_ID = "import_id"
        /** Whether the route's recipe file was handled, so it isn't imported again after recreation. */
        private const val KEY_FILE_HANDLED = "file_handled"
        /** Photos taken with the camera app for the next import (content URIs from [CameraPhotoFiles]). */
        private const val KEY_PHOTOS = "camera_photos"
        /** The file the camera app is saving a photo to (the app may be stopped meanwhile). */
        private const val KEY_PENDING_PHOTO = "pending_camera_photo"
        private const val TAG = "AddRecipeViewModel"
    }

    val url: StateFlow<String> = savedStateHandle.getStateFlow(
        KEY_URL_INPUT,
        savedStateHandle.toRoute<Route.AddRecipe>().url ?: ""
    )

    /** State before an import is being tracked: idle, validation errors, or starting. */
    private val localState = MutableStateFlow<AddRecipeUiState>(AddRecipeUiState.Idle)

    private val importId: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_IMPORT_ID, null)

    /** Photos taken for the next import, in page order. */
    val capturedPhotos: StateFlow<List<String>> = savedStateHandle.getStateFlow(KEY_PHOTOS, arrayListOf<String>())

    /** Whether another photo can be added to [capturedPhotos]. */
    val canTakePhoto: StateFlow<Boolean> = capturedPhotos
        .map { it.size < ResourceLimits.MAX_PHOTOS_PER_RECIPE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

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

    val hasApiKey: StateFlow<Boolean> = aiSettings.model(ModelRole.IMPORT)
        .map { it != null }
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
        // Photos taken before process death may since have been cleaned up
        if (capturedPhotos.value.isNotEmpty()) {
            viewModelScope.launch {
                val existing = capturedPhotos.value.filter { cameraPhotos.hasPhoto(it.toUri()) }
                if (existing.size != capturedPhotos.value.size) savedStateHandle[KEY_PHOTOS] = ArrayList(existing)
            }
        }
        val route = savedStateHandle.toRoute<Route.AddRecipe>()
        if (route.fileUris.isNotEmpty() && savedStateHandle.get<Boolean>(KEY_FILE_HANDLED) != true) {
            savedStateHandle[KEY_FILE_HANDLED] = true
            onFilesPicked(route.fileUris.map { it.toUri() }, route.fileMimeType)
        }
    }

    fun onUrlChange(url: String) {
        savedStateHandle[KEY_URL_INPUT] = url
    }

    /** Validates the URL and opens it in the in-app browser ([AddRecipeEvent.OpenBrowser]). */
    fun onImportClick() {
        savedStateHandle[KEY_IMPORT_ID] = null
        val input = url.value.trim()
        if (input.isBlank()) {
            localState.value = AddRecipeUiState.Error(UserMessage(R.string.error_enter_url), isUrlError = true)
            return
        }

        // Accept pasted text like "Recipe title https://example.com/recipe"
        val currentUrl = extractFirstUrl(input) ?: input
        if (!currentUrl.startsWith("http://", ignoreCase = true) && !currentUrl.startsWith("https://", ignoreCase = true)) {
            localState.value = AddRecipeUiState.Error(UserMessage(R.string.error_url_scheme), isUrlError = true)
            return
        }

        localState.value = AddRecipeUiState.Idle
        _events.trySend(AddRecipeEvent.OpenBrowser(currentUrl))
    }

    /**
     * Import the page chosen in the in-app browser (or, without a captured page, download it).
     * The captured file is handed to the import.
     */
    fun importPage(page: BrowsedPage) {
        launchImport("Failed to start import of ${page.url}") { importQueue.startUrlImport(page.url, page.htmlPath) }
    }

    /**
     * Import the recipe in a picked or shared Markdown, text, HTML or PDF file or photo, or send
     * a recipe archive to the selection screen ([AddRecipeEvent.OpenImportSelection]).
     *
     * @param mimeTypeHint the type the app that shared the file gave, if any
     */
    fun onFilePicked(uri: Uri, mimeTypeHint: String? = null) {
        onFilesPicked(listOf(uri), mimeTypeHint)
    }

    /**
     * Import the recipe in picked or shared files: one file (see [onFilePicked]), or several
     * photos of one recipe, in page order.
     */
    fun onFilesPicked(uris: List<Uri>, mimeTypeHint: String? = null) {
        if (uris.size == 1) {
            importFile(uris.single(), mimeTypeHint)
            return
        }
        launchImport("Failed to start import of ${uris.size} files") {
            importQueue.startRecipeFileImport(inspectPhotos(uris, mimeTypeHint))
        }
    }

    /** @throws RecipeErrorException if one of the files isn't a photo */
    private suspend fun inspectPhotos(uris: List<Uri>, mimeTypeHint: String?): List<RecipeFile> = uris.map { uri ->
        importFileInspector.recipeFile(uri, mimeTypeHint)?.takeIf { it.format == RecipeFileFormat.IMAGE }
            ?: throw RecipeErrorException(RecipeError.ImportFile(ImportFileFailure.NOT_ALL_PHOTOS))
    }

    private fun importFile(uri: Uri, mimeTypeHint: String?) {
        launchImport("Failed to start import of file $uri") { pending ->
            val recipeFile = importFileInspector.recipeFile(uri, mimeTypeHint)
            when {
                recipeFile != null -> importQueue.startRecipeFileImport(listOf(recipeFile))
                pending.cancelled -> null
                else -> {
                    _events.send(AddRecipeEvent.OpenImportSelection(ImportType.guessFromUri(uri.toString()), uri))
                    null
                }
            }
        }
    }

    /**
     * Runs [start], then shows the started import's progress (null: nothing was started).
     * [onStarted] runs once the import is kept (it wasn't cancelled meanwhile).
     */
    private fun launchImport(
        failureLog: String,
        onStarted: () -> Unit = {},
        start: suspend (PendingStart) -> ImportQueue.StartedImport?
    ) {
        savedStateHandle[KEY_IMPORT_ID] = null
        localState.value = AddRecipeUiState.Loading(ImportStage.QUEUED)
        val pending = PendingStart()
        pendingStart = pending
        viewModelScope.launch {
            val started = try {
                start(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = (e as? RecipeErrorSource)?.recipeError
                // A known error is about the input (e.g. a file that is too large), not a bug
                if (error != null) Log.w(TAG, failureLog, e) else ErrorLog.e(TAG, failureLog, e)
                if (!pending.cancelled) {
                    localState.value = AddRecipeUiState.Error(error?.toUserMessage() ?: UserMessage(R.string.error_start_import))
                }
                return@launch
            } finally {
                if (pendingStart === pending) pendingStart = null
            }
            if (started == null) {
                if (!pending.cancelled) localState.value = AddRecipeUiState.Idle
                return@launch
            }
            if (pending.cancelled) {
                importQueue.cancelImport(started.importId)
                return@launch
            }
            // From here on the import's own state is shown; it is derived by ID, so even an
            // import that already finished is reported.
            savedStateHandle[KEY_IMPORT_ID] = started.importId
            onStarted()
        }
    }

    /** Asks the camera app for a photo of the recipe ([AddRecipeEvent.TakePhoto]). */
    fun onTakePhotoClick() {
        if (!canTakePhoto.value || cameraOpen) return
        cameraOpen = true
        viewModelScope.launch {
            val uri = try {
                cameraPhotos.newPhotoUri()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Couldn't create a file for a camera photo", e)
                cameraOpen = false
                localState.value = AddRecipeUiState.Error(UserMessage(R.string.error_take_photo))
                return@launch
            }
            savedStateHandle[KEY_PENDING_PHOTO] = uri.toString()
            _events.send(AddRecipeEvent.TakePhoto(uri))
        }
    }

    /**
     * The camera app is done: keep the photo if it saved one. That decides rather than [success],
     * since some camera apps report a saved photo as cancelled.
     */
    fun onPhotoTaken(success: Boolean) {
        cameraOpen = false
        val uri = savedStateHandle.get<String>(KEY_PENDING_PHOTO)?.toUri() ?: return
        savedStateHandle[KEY_PENDING_PHOTO] = null
        viewModelScope.launch {
            val saved = cameraPhotos.hasPhoto(uri)
            if (saved != success) Log.i(TAG, "The camera app reported success=$success; photo saved: $saved")
            if (saved) {
                localState.value = AddRecipeUiState.Idle
                savedStateHandle[KEY_PHOTOS] = ArrayList(capturedPhotos.value + uri.toString())
            } else {
                cameraPhotos.delete(uri)
            }
        }
    }

    /** There is no camera app to take a photo with. */
    fun onCameraUnavailable() {
        onPhotoTaken(success = false)
        localState.value = AddRecipeUiState.Error(UserMessage(R.string.error_no_camera))
    }

    /** Import the recipe in the photos taken; they are deleted once the import has its own copies. */
    fun importCapturedPhotos() {
        val photos = capturedPhotos.value.map { it.toUri() }
        if (photos.isEmpty() || pendingStart != null) return
        launchImport("Failed to start import of ${photos.size} camera photos", onStarted = ::discardCapturedPhotos) {
            importQueue.startRecipeFileImport(inspectPhotos(photos, "image/jpeg"))
        }
    }

    fun discardCapturedPhotos() {
        val photos = capturedPhotos.value.map { it.toUri() }
        savedStateHandle[KEY_PHOTOS] = arrayListOf<String>()
        viewModelScope.launch {
            withContext(NonCancellable) { photos.forEach { cameraPhotos.delete(it) } }
        }
    }

    fun cancelImport() {
        // If the import is still being started, cancel it as soon as its ID is known.
        pendingStart?.cancelled = true
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
    /** [isUrlError]: the message is about the URL field (marked as invalid). */
    data class Error(val message: UserMessage, val isUrlError: Boolean = false) : AddRecipeUiState()
    object NoApiKey : AddRecipeUiState()
}

sealed interface AddRecipeEvent {
    /** Show [url] in the in-app browser so the user can confirm it. */
    data class OpenBrowser(val url: String) : AddRecipeEvent

    /** Have the camera app save a photo of the recipe to [uri]. */
    data class TakePhoto(val uri: Uri) : AddRecipeEvent

    /** [uri] is a recipe archive (.lorecipes or Paprika export) to choose recipes from. */
    data class OpenImportSelection(val importType: ImportType, val uri: Uri) : AddRecipeEvent

    /** [recipeId] is null if the import succeeded without reporting the new recipe. */
    data class RecipeAdded(val recipeId: String?) : AddRecipeEvent
}
