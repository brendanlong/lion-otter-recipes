package com.lionotter.recipes.ui.screens.importselection

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.domain.util.ZipImportHelper
import com.lionotter.recipes.ui.state.InProgressRecipeManager
import com.lionotter.recipes.worker.PaprikaImportWorker
import com.lionotter.recipes.worker.ZipImportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val recipeRepository: RecipeRepository,
    private val paprikaParser: PaprikaParser,
    private val recipeSerializer: RecipeSerializer,
    private val zipImportHelper: ZipImportHelper,
    private val workManager: WorkManager,
    private val inProgressRecipeManager: InProgressRecipeManager
) : ViewModel() {

    companion object {
        private const val TAG = "ImportSelectionVM"
    }

    private val _uiState = MutableStateFlow<ImportSelectionUiState>(ImportSelectionUiState.Loading)
    val uiState: StateFlow<ImportSelectionUiState> = _uiState.asStateFlow()

    private var currentImportType: ImportType? = null
    private var currentFileUri: Uri? = null

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
     */
    fun parseFile(uri: Uri, importType: ImportType, contentResolver: ContentResolver) {
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
        }
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
                _uiState.value = ImportSelectionUiState.Error("Could not open file: ${e.message}")
                return@withContext
            }

            try {
                val recipes = inputStream.use { paprikaParser.parseExport(it) }
                if (recipes.isEmpty()) {
                    _uiState.value = ImportSelectionUiState.Error("No recipes found in Paprika export")
                    return@withContext
                }

                val existingRecipes = recipeRepository.getAllRecipeIdsAndNames()
                val existingNames = existingRecipes.map { it.name.lowercase() }.toSet()

                val items = recipes.mapIndexed { index, recipe ->
                    val exists = recipe.name.lowercase() in existingNames
                    ImportSelectionItem(
                        id = index.toString(),
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
                _uiState.value = ImportSelectionUiState.Error("Could not open file: ${e.message}")
                return@withContext
            }

            try {
                val folderContents = inputStream.use { zipImportHelper.readZipContents(it) }
                if (folderContents == null || folderContents.isEmpty()) {
                    _uiState.value = ImportSelectionUiState.Error("No recipes found in file")
                    return@withContext
                }

                val existingRecipes = recipeRepository.getAllRecipeIdsAndNames()
                val existingIds = existingRecipes.map { it.id }.toSet()
                val existingNames = existingRecipes.map { it.name.lowercase() }.toSet()

                val items = folderContents.entries
                    .filter { it.key != ZipImportHelper.MEAL_PLANS_FOLDER }
                    .mapNotNull { (_, files) ->
                        val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME]
                            ?: return@mapNotNull null
                        try {
                            val recipe = recipeSerializer.deserializeRecipe(jsonContent)
                            val existsById = recipe.id in existingIds
                            val existsByName = recipe.name.lowercase() in existingNames
                            val exists = existsById || existsByName
                            ImportSelectionItem(
                                id = recipe.id,
                                name = recipe.name,
                                isSelected = !exists,
                                alreadyExists = exists
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to deserialize recipe for selection", e)
                            null
                        }
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
        _uiState.value = ImportSelectionUiState.Ready(updatedItems)
    }

    fun selectAll() {
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return
        _uiState.value = ImportSelectionUiState.Ready(
            current.items.map { it.copy(isSelected = true) }
        )
    }

    fun deselectAll() {
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return
        _uiState.value = ImportSelectionUiState.Ready(
            current.items.map { it.copy(isSelected = false) }
        )
    }

    /**
     * Enqueue the import as a WorkManager job and return immediately.
     * All import types follow the same pattern: enqueue work, register in-progress entry, navigate to RecipeList.
     */
    fun importSelected(onResult: (ImportResult) -> Unit) {
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return

        val selectedItems = current.items.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        val fileUri = currentFileUri ?: run {
            onResult(ImportResult.Error("No file URI available"))
            return
        }

        val importId = UUID.randomUUID().toString()

        when (currentImportType) {
            ImportType.PAPRIKA -> {
                val selectedNames = selectedItems.map { it.name }.toSet()
                val workRequest = OneTimeWorkRequestBuilder<PaprikaImportWorker>()
                    .setInputData(
                        PaprikaImportWorker.createInputData(
                            fileUri = fileUri,
                            importId = importId,
                            selectedRecipeNames = selectedNames
                        )
                    )
                    .addTag(PaprikaImportWorker.TAG_PAPRIKA_IMPORT)
                    .build()

                inProgressRecipeManager.addInProgressRecipe(
                    id = importId,
                    name = "Importing from Paprika\u2026",
                    workManagerId = workRequest.id.toString()
                )
                workManager.enqueue(workRequest)
                onResult(ImportResult.ImportStarted)
            }
            ImportType.LORECIPES -> {
                val selectedIds = selectedItems.map { it.id }.toSet()
                val workRequest = OneTimeWorkRequestBuilder<ZipImportWorker>()
                    .setInputData(
                        ZipImportWorker.createInputData(
                            fileUri = fileUri,
                            importId = importId,
                            selectedRecipeIds = selectedIds
                        )
                    )
                    .addTag(ZipImportWorker.TAG_ZIP_IMPORT)
                    .build()

                val importName = if (selectedItems.size == 1) {
                    "Importing ${selectedItems[0].name}\u2026"
                } else {
                    "Importing ${selectedItems.size} recipes\u2026"
                }
                inProgressRecipeManager.addInProgressRecipe(
                    id = importId,
                    name = importName,
                    workManagerId = workRequest.id.toString()
                )
                workManager.enqueue(workRequest)
                onResult(ImportResult.ImportStarted)
            }
            null -> onResult(ImportResult.Error("Unknown import type"))
        }
    }
}
