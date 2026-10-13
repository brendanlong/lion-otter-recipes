package com.lionotter.recipes.ui.screens.importselection

import com.lionotter.recipes.domain.RecipeErrorSource
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.domain.ArchiveFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.domain.util.ZipImportHelper
import com.lionotter.recipes.imports.ImportQueue
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import com.lionotter.recipes.worker.ImportInputStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class ImportSelectionViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val paprikaParser: PaprikaParser,
    private val zipImportHelper: ZipImportHelper,
    private val inputStore: ImportInputStore,
    private val importQueue: ImportQueue,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val TAG = "ImportSelectionVM"
        private const val KEY_SELECTED_IDS = "import_selection_selected_ids"
        private const val KEY_STAGED_IMPORT_ID = "import_selection_staged_id"

        /** New recipes first, then by name. */
        private val SELECTION_ORDER = compareBy<ImportSelectionItem> { it.alreadyExists }.thenBy { it.name.lowercase() }
    }

    private val _uiState = MutableStateFlow<ImportSelectionUiState>(ImportSelectionUiState.Loading)
    val uiState: StateFlow<ImportSelectionUiState> = _uiState.asStateFlow()

    /** One-shot results of [importSelected], collected by the screen. */
    private val _events = Channel<ImportSelectionEvent>(Channel.BUFFERED)
    val events: Flow<ImportSelectionEvent> = _events.receiveAsFlow()

    private var currentImportType: ImportType? = null

    /** The import ID whose directory holds this screen's copy of the file. */
    private var stagedImportId: String? = null
    private var importStarted = false

    init {
        loadFile()
    }

    /**
     * Copy the file from the navigation argument into app storage (once per screen, surviving
     * process death via the saved import ID) and parse it to populate the selection list.
     *
     * The type argument is only a hint: the actual type is detected from the ZIP contents,
     * since content URIs often have opaque paths that don't reflect the file extension.
     */
    private fun loadFile() {
        val route = savedStateHandle.toRoute<Route.ImportSelection>()
        val uriArg = route.uri
        val typeHint = route.importType
        viewModelScope.launch {
            val importId = try {
                stageFile(uriArg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Could not copy import file", e)
                _uiState.value = ImportSelectionUiState.Error(UserMessage(R.string.error_open_import_file))
                return@launch
            }
            stagedImportId = importId
            val file = inputStore.sourceFile(importId)

            val actualType = withContext(ioDispatcher) { detectImportType(file) }
            if (actualType == null) {
                Log.w(TAG, "Not a recipe export (hint was $typeHint)")
                _uiState.value = archiveError(ArchiveFailure.NOT_AN_EXPORT)
                return@launch
            }
            currentImportType = actualType

            _uiState.value = withContext(ioDispatcher) {
                when (actualType) {
                    ImportType.PAPRIKA -> parsePaprikaFile(file)
                    ImportType.LORECIPES -> parseZipContents(file)
                }
            }
            applySavedSelection()
        }
    }

    private suspend fun stageFile(uriArg: String): String {
        savedStateHandle.get<String>(KEY_STAGED_IMPORT_ID)?.let { existing ->
            if (inputStore.hasSource(existing)) {
                inputStore.touch(existing)
                return existing
            }
        }
        val importId = UUID.randomUUID().toString()
        try {
            inputStore.copySource(importId, uriArg.toUri())
        } catch (e: Exception) {
            inputStore.deleteImport(importId)
            throw e
        }
        savedStateHandle[KEY_STAGED_IMPORT_ID] = importId
        return importId
    }

    private fun archiveError(reason: ArchiveFailure) =
        ImportSelectionUiState.Error(RecipeError.Archive(reason).toUserMessage())

    override fun onCleared() {
        // Leaving without importing: the copy is no longer needed.
        if (!importStarted) stagedImportId?.let(inputStore::deleteImport)
    }

    /**
     * Dedupes parsed items by id (a malformed export could repeat ids, which would crash the
     * LazyColumn) and restores the user's selection after process death.
     */
    private fun applySavedSelection() {
        val state = _uiState.value as? ImportSelectionUiState.Ready ?: return
        val savedIds = savedStateHandle.get<ArrayList<String>>(KEY_SELECTED_IDS)?.toSet()
        val items = state.items
            .distinctBy { it.id }
            .map { item ->
                if (savedIds != null) item.copy(isSelected = item.id in savedIds) else item
            }
        updateItems(items)
    }

    private fun updateItems(items: List<ImportSelectionItem>) {
        _uiState.value = ImportSelectionUiState.Ready(items)
        savedStateHandle[KEY_SELECTED_IDS] = ArrayList(items.filter { it.isSelected }.map { it.id })
    }

    /**
     * Detect import type by peeking at ZIP entry names.
     * - Paprika exports contain `.paprikarecipe` entries (gzip-compressed JSON)
     * - .lorecipes files contain folders with `recipe.json` files
     * Returns null if the file isn't a ZIP or contains neither.
     */
    private fun detectImportType(file: File): ImportType? {
        return try {
            ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        if (name.endsWith(".paprikarecipe")) {
                            return@use ImportType.PAPRIKA
                        }
                        if (name.endsWith(RecipeSerializer.RECIPE_JSON_FILENAME)) {
                            return@use ImportType.LORECIPES
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read import file as a ZIP", e)
            null
        }
    }

    /** Blocking; call on [ioDispatcher]. */
    private suspend fun parsePaprikaFile(file: File): ImportSelectionUiState = try {
        val recipes = file.inputStream().use { paprikaParser.parseIndex(BufferedInputStream(it)) }
        if (recipes.isEmpty()) {
            archiveError(ArchiveFailure.NO_RECIPES)
        } else {
            val existingNames = recipeRepository.getAllRecipeIdsAndNames().map { it.name.lowercase() }.toSet()
            val items = recipes.distinctBy { it.uid }.map { recipe ->
                val exists = recipe.name.lowercase() in existingNames
                ImportSelectionItem(
                    id = recipe.uid,
                    name = recipe.name,
                    isSelected = !exists,
                    alreadyExists = exists
                )
            }
            ImportSelectionUiState.Ready(items.sortedWith(SELECTION_ORDER))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.e(TAG, "Failed to parse Paprika export", e)
        archiveError(ArchiveFailure.UNREADABLE)
    }

    /** Blocking; call on [ioDispatcher]. */
    private suspend fun parseZipContents(file: File): ImportSelectionUiState = try {
        val index = file.inputStream().use { zipImportHelper.readRecipeIndex(BufferedInputStream(it)) }
        val existingRecipes = recipeRepository.getAllRecipeIdsAndNames()
        val existingIds = existingRecipes.map { it.id }.toSet()
        val existingNames = existingRecipes.map { it.name.lowercase() }.toSet()

        val items = index
            .distinctBy { it.id }
            .map { recipe ->
                val exists = recipe.id in existingIds || recipe.name.lowercase() in existingNames
                ImportSelectionItem(
                    id = recipe.id,
                    name = recipe.name,
                    isSelected = !exists,
                    alreadyExists = exists
                )
            }
            .sortedWith(SELECTION_ORDER)

        if (items.isEmpty()) archiveError(ArchiveFailure.NO_RECIPES) else ImportSelectionUiState.Ready(items)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.e(TAG, "Failed to read ZIP contents", e)
        archiveError(ArchiveFailure.UNREADABLE)
    }

    /**
     * Check if this is a single-recipe .lorecipes file with a new recipe.
     * Returns true if auto-import should be used (skip the selection screen).
     */
    fun shouldAutoImport(): Boolean {
        val state = _uiState.value
        if (state !is ImportSelectionUiState.Ready) return false
        if (currentImportType != ImportType.LORECIPES) return false
        return state.items.size == 1 && !state.items[0].alreadyExists
    }

    fun toggleItem(itemId: String) {
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return

        val updatedItems = current.items.map { item ->
            if (item.id == itemId) item.copy(isSelected = !item.isSelected) else item
        }
        updateItems(updatedItems)
    }

    fun selectAll() {
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return
        updateItems(current.items.map { it.copy(isSelected = true) })
    }

    fun deselectAll() {
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return
        updateItems(current.items.map { it.copy(isSelected = false) })
    }

    /**
     * Enqueue the import as a WorkManager job; the outcome is delivered through [results].
     * Repeated calls (double taps, auto-import re-running after recreation) are ignored.
     */
    fun importSelected() {
        if (importStarted) return
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return

        val selectedItems = current.items.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        val importId = stagedImportId
        val importType = currentImportType
        if (importId == null || importType == null) {
            // Ready implies both are set
            ErrorLog.e(TAG, "Import requested without a staged file ($importId) or type ($importType)")
            _events.trySend(ImportSelectionEvent.ShowMessage(UserMessage(R.string.error_unexpected)))
            return
        }
        importStarted = true

        // Pass null when everything is selected so the worker doesn't need the full ID list.
        val selectedIds = selectedItems.map { it.id }.toSet()
            .takeIf { selectedItems.size < current.items.size }
        val importName = if (selectedItems.size == 1) {
            "Importing ${selectedItems[0].name}\u2026"
        } else if (importType == ImportType.PAPRIKA) {
            "Importing ${selectedItems.size} recipes from Paprika\u2026"
        } else {
            "Importing ${selectedItems.size} recipes\u2026"
        }

        viewModelScope.launch {
            try {
                when (importType) {
                    ImportType.PAPRIKA ->
                        importQueue.startPaprikaImport(importId, selectedIds, importName)
                    ImportType.LORECIPES ->
                        importQueue.startZipImport(importId, selectedIds, importName)
                }
                _events.send(ImportSelectionEvent.ImportStarted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to start import", e)
                importStarted = false
                val message = (e as? RecipeErrorSource)?.recipeError?.toUserMessage()
                    ?: UserMessage(R.string.error_start_import)
                _events.send(ImportSelectionEvent.ShowMessage(message))
            }
        }
    }
}
