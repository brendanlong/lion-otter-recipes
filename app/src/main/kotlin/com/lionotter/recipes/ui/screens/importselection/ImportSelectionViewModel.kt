package com.lionotter.recipes.ui.screens.importselection

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.domain.util.ZipImportHelper
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class ImportSelectionViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val appContext: Context,
    private val recipeRepository: IRecipeRepository,
    private val paprikaParser: PaprikaParser,
    private val recipeSerializer: RecipeSerializer,
    private val zipImportHelper: ZipImportHelper,
    private val workManager: WorkManager,
    private val inProgressRecipeManager: InProgressRecipeManager
) : ViewModel() {

    companion object {
        private const val TAG = "ImportSelectionVM"
        private const val ARG_IMPORT_TYPE = "importType"
        private const val ARG_URI = "uri"
        private const val KEY_SELECTED_IDS = "import_selection_selected_ids"
    }

    private val _uiState = MutableStateFlow<ImportSelectionUiState>(ImportSelectionUiState.Loading)
    val uiState: StateFlow<ImportSelectionUiState> = _uiState.asStateFlow()

    private var currentImportType: ImportType? = null
    private var currentFileUri: Uri? = null
    private var importStarted = false

    init {
        val uriArg = savedStateHandle.get<String>(ARG_URI)
        if (uriArg != null) {
            val typeHint = when (savedStateHandle.get<String>(ARG_IMPORT_TYPE)) {
                "paprika" -> ImportType.PAPRIKA
                else -> ImportType.LORECIPES
            }
            parseFile(uriArg.toUri(), typeHint, appContext.contentResolver)
        }
    }

    /**
     * Result of calling importSelected().
     */
    sealed class ImportResult {
        /** Import work was enqueued via WorkManager. Navigate to RecipeList. */
        object ImportStarted : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    /**
     * Parse the given file and populate the selection list.
     * The importType is used as a hint but may be overridden by auto-detection
     * based on the actual ZIP contents (since Android content URIs often
     * have opaque paths that don't reflect the file extension).
     *
     * The file is parsed once per ViewModel (normally from the navigation arguments in init);
     * calling this again for the same URI is a no-op, so screen recreation doesn't reset the
     * user's selection.
     */
    fun parseFile(uri: Uri, importType: ImportType, contentResolver: ContentResolver) {
        if (uri == currentFileUri) return
        currentFileUri = uri
        currentImportType = importType
        _uiState.value = ImportSelectionUiState.Loading

        viewModelScope.launch {
            val detectedType = withContext(Dispatchers.IO) {
                detectImportType(uri, contentResolver)
            }
            val actualType = detectedType ?: importType
            currentImportType = actualType

            when (actualType) {
                ImportType.PAPRIKA -> parsePaprikaFile(uri, contentResolver)
                ImportType.LORECIPES -> parseZipContents(uri, contentResolver)
            }
            applySavedSelection()
        }
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
     * Returns null if detection is inconclusive.
     */
    private fun detectImportType(uri: Uri, contentResolver: ContentResolver): ImportType? {
        return try {
            contentResolver.openInputStream(uri)?.use { rawStream ->
                val buffered = BufferedInputStream(rawStream)
                ZipInputStream(buffered).use { zip ->
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect import type, falling back to hint", e)
            null
        }
    }

    private suspend fun parsePaprikaFile(uri: Uri, contentResolver: ContentResolver) {
        withContext(Dispatchers.IO) {
            val inputStream: InputStream = try {
                contentResolver.openInputStream(uri)
                    ?: run {
                        _uiState.value = ImportSelectionUiState.Error("Could not open file")
                        return@withContext
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Could not open Paprika export", e)
                _uiState.value = ImportSelectionUiState.Error("Could not open file: ${e.message}")
                return@withContext
            }

            try {
                val recipes = inputStream.use { paprikaParser.parseIndex(BufferedInputStream(it)) }
                if (recipes.isEmpty()) {
                    _uiState.value = ImportSelectionUiState.Error("No recipes found in Paprika export")
                    return@withContext
                }

                val existingRecipes = recipeRepository.getAllRecipeIdsAndNames()
                val existingNames = existingRecipes.map { it.name.lowercase() }.toSet()

                val items = recipes.distinctBy { it.uid }.map { recipe ->
                    val exists = recipe.name.lowercase() in existingNames
                    ImportSelectionItem(
                        id = recipe.uid,
                        name = recipe.name,
                        isSelected = !exists,
                        alreadyExists = exists
                    )
                }.sortedWith(compareBy<ImportSelectionItem> { it.alreadyExists }.thenBy { it.name.lowercase() })

                _uiState.value = ImportSelectionUiState.Ready(items)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Paprika export", e)
                _uiState.value = ImportSelectionUiState.Error("Failed to read Paprika export: ${e.message}")
            }
        }
    }

    private suspend fun parseZipContents(uri: Uri, contentResolver: ContentResolver) {
        withContext(Dispatchers.IO) {
            val inputStream: InputStream = try {
                contentResolver.openInputStream(uri)
                    ?: run {
                        _uiState.value = ImportSelectionUiState.Error("Could not open file")
                        return@withContext
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Could not open import file", e)
                _uiState.value = ImportSelectionUiState.Error("Could not open file: ${e.message}")
                return@withContext
            }

            try {
                val index = inputStream.use { zipImportHelper.readRecipeIndex(BufferedInputStream(it)) }

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
                    .sortedWith(compareBy<ImportSelectionItem> { it.alreadyExists }.thenBy { it.name.lowercase() })

                if (items.isEmpty()) {
                    _uiState.value = ImportSelectionUiState.Error("No valid recipes found in file")
                    return@withContext
                }

                _uiState.value = ImportSelectionUiState.Ready(items)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read ZIP contents", e)
                _uiState.value = ImportSelectionUiState.Error("Failed to read file: ${e.message}")
            }
        }
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
     * Enqueue the import as a WorkManager job and return immediately.
     * All import types follow the same pattern: enqueue work, register in-progress entry, navigate to RecipeList.
     * Repeated calls (double taps, auto-import re-running after recreation) are ignored.
     */
    fun importSelected(onResult: (ImportResult) -> Unit) {
        if (importStarted) return
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return

        val selectedItems = current.items.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        val fileUri = currentFileUri ?: run {
            onResult(ImportResult.Error("No file URI available"))
            return
        }
        importStarted = true

        val importType = currentImportType ?: run {
            importStarted = false
            onResult(ImportResult.Error("Unknown import type"))
            return
        }
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
                        inProgressRecipeManager.startPaprikaImport(fileUri, selectedIds, importName)
                    ImportType.LORECIPES ->
                        inProgressRecipeManager.startZipImport(fileUri, selectedIds, importName)
                }
                onResult(ImportResult.ImportStarted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start import", e)
                importStarted = false
                onResult(ImportResult.Error("Couldn't start the import: ${e.message}"))
            }
        }
    }
}
