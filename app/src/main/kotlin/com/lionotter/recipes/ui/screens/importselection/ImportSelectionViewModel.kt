package com.lionotter.recipes.ui.screens.importselection

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.data.paprika.PaprikaRecipe
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.util.RecipeSerializer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * ViewModel for the import selection screen. Handles parsing of import files
 * (Paprika, .lorecipes, and ZIP backup) to extract recipe names, checking
 * for duplicates, and performing the actual import of selected recipes.
 */
@HiltViewModel
class ImportSelectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recipeRepository: RecipeRepository,
    private val recipeSerializer: RecipeSerializer,
    private val paprikaParser: PaprikaParser,
    private val mealPlanRepository: MealPlanRepository,
    private val json: Json,
    private val imageDownloadService: ImageDownloadService
) : ViewModel() {

    companion object {
        private const val TAG = "ImportSelection"
        private const val MEAL_PLANS_FOLDER = "meal-plans"
    }

    private val _uiState = MutableStateFlow<ImportSelectionUiState>(ImportSelectionUiState.Loading)
    val uiState: StateFlow<ImportSelectionUiState> = _uiState.asStateFlow()

    // Parsed data held in memory for import
    private var parsedPaprikaRecipes: List<PaprikaRecipe>? = null
    private var parsedLorecipesFolders: Map<String, Map<String, String>>? = null
    private var currentImportType: ImportType? = null
    private var currentFileUri: Uri? = null
    private var alreadyParsed = false

    private suspend fun downloadImageIfNeeded(imageUrl: String?): String? {
        if (imageUrl == null) return null
        if (imageUrl.startsWith("file://")) {
            val path = imageUrl.removePrefix("file://")
            return if (java.io.File(path).exists()) imageUrl else null
        }
        return imageDownloadService.downloadAndStore(imageUrl)
    }

    enum class ImportType {
        PAPRIKA,
        LORECIPES,
        ZIP_BACKUP
    }

    sealed class ImportResult {
        /** Paprika import: recipes were parsed and selected, ready for WorkManager */
        data class PaprikaSelected(
            val fileUri: Uri,
            val selectedRecipeNames: Set<String>
        ) : ImportResult()

        /** .lorecipes or ZIP import completed synchronously */
        data class DirectImportComplete(
            val importedCount: Int,
            val skippedCount: Int,
            val failedCount: Int,
            val importedRecipeId: String? = null
        ) : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    /**
     * Parse a file to extract recipe names for selection.
     */
    fun parseFile(uri: Uri, importType: ImportType) {
        if (alreadyParsed) return
        alreadyParsed = true
        currentFileUri = uri
        currentImportType = importType
        _uiState.value = ImportSelectionUiState.Loading

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (importType) {
                    ImportType.PAPRIKA -> parsePaprikaFile(uri)
                    ImportType.LORECIPES, ImportType.ZIP_BACKUP -> parseZipContents(uri)
                }
            }
        }
    }

    private suspend fun parsePaprikaFile(uri: Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: run {
                    _uiState.value = ImportSelectionUiState.Error("Could not open file")
                    return
                }

            val recipes = inputStream.use { paprikaParser.parseExport(it) }

            if (recipes.isEmpty()) {
                _uiState.value = ImportSelectionUiState.Error("No recipes found in Paprika export")
                return
            }

            parsedPaprikaRecipes = recipes

            val existingRecipes = recipeRepository.getAllRecipeIdsAndNames()
            val existingNames = existingRecipes.map { it.name.lowercase() }.toSet()

            val items = recipes.map { recipe ->
                val alreadyExists = recipe.name.lowercase() in existingNames
                ImportSelectionItem(
                    id = recipe.uid,
                    name = recipe.name,
                    isSelected = !alreadyExists,
                    alreadyExists = alreadyExists
                )
            }.sortedBy { it.name.lowercase() }

            _uiState.value = ImportSelectionUiState.Ready(items)
        } catch (e: Exception) {
            _uiState.value = ImportSelectionUiState.Error("Failed to read Paprika export: ${e.message}")
        }
    }

    private suspend fun parseZipContents(uri: Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: run {
                    _uiState.value = ImportSelectionUiState.Error("Could not open file")
                    return
                }

            val folderContents = mutableMapOf<String, MutableMap<String, String>>()

            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val pathParts = entry.name.split("/", limit = 2)
                        if (pathParts.size == 2) {
                            val folderName = pathParts[0]
                            val fileName = pathParts[1]
                            val content = zipIn.readBytes().toString(Charsets.UTF_8)
                            folderContents.getOrPut(folderName) { mutableMapOf() }[fileName] = content
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            if (folderContents.isEmpty()) {
                _uiState.value = ImportSelectionUiState.Error("No recipes found in file")
                return
            }

            parsedLorecipesFolders = folderContents

            val existingRecipes = recipeRepository.getAllRecipeIdsAndNames()
            val existingIds = existingRecipes.map { it.id }.toSet()
            val existingNames = existingRecipes.map { it.name.lowercase() }.toSet()

            // Filter out meal-plans folder - only show recipe folders
            val items = folderContents
                .filter { (folderName, _) -> folderName != MEAL_PLANS_FOLDER }
                .mapNotNull { (_, files) ->
                    val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME] ?: return@mapNotNull null
                    try {
                        val recipe = recipeSerializer.deserializeRecipe(jsonContent)
                        val alreadyExists = recipe.id in existingIds || recipe.name.lowercase() in existingNames
                        ImportSelectionItem(
                            id = recipe.id,
                            name = recipe.name,
                            isSelected = !alreadyExists,
                            alreadyExists = alreadyExists
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedBy { it.name.lowercase() }

            if (items.isEmpty()) {
                _uiState.value = ImportSelectionUiState.Error("No valid recipes found in file")
                return
            }

            _uiState.value = ImportSelectionUiState.Ready(items)
        } catch (e: Exception) {
            _uiState.value = ImportSelectionUiState.Error("Failed to read file: ${e.message}")
        }
    }

    fun toggleItem(itemId: String) {
        val current = _uiState.value
        if (current is ImportSelectionUiState.Ready) {
            val updatedItems = current.items.map { item ->
                if (item.id == itemId) item.copy(isSelected = !item.isSelected) else item
            }
            _uiState.value = ImportSelectionUiState.Ready(updatedItems)
        }
    }

    fun selectAll() {
        val current = _uiState.value
        if (current is ImportSelectionUiState.Ready) {
            val updatedItems = current.items.map { it.copy(isSelected = true) }
            _uiState.value = ImportSelectionUiState.Ready(updatedItems)
        }
    }

    fun deselectAll() {
        val current = _uiState.value
        if (current is ImportSelectionUiState.Ready) {
            val updatedItems = current.items.map { it.copy(isSelected = false) }
            _uiState.value = ImportSelectionUiState.Ready(updatedItems)
        }
    }

    /**
     * Import the selected recipes. Returns the result via the callback.
     * For Paprika, returns a PaprikaSelected result that the caller should
     * use to start the WorkManager import.
     * For .lorecipes and ZIP, performs the import directly.
     */
    fun importSelected(onResult: (ImportResult) -> Unit) {
        val current = _uiState.value
        if (current !is ImportSelectionUiState.Ready) return

        val selectedIds = current.items.filter { it.isSelected }.map { it.id }.toSet()
        if (selectedIds.isEmpty()) return

        when (currentImportType) {
            ImportType.PAPRIKA -> {
                val selectedNames = current.items
                    .filter { it.isSelected }
                    .map { it.name }
                    .toSet()
                onResult(ImportResult.PaprikaSelected(currentFileUri!!, selectedNames))
            }
            ImportType.LORECIPES, ImportType.ZIP_BACKUP -> {
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        importZipSelected(selectedIds)
                    }
                    onResult(result)
                }
            }
            null -> onResult(ImportResult.Error("Unknown import type"))
        }
    }

    private suspend fun importZipSelected(selectedIds: Set<String>): ImportResult {
        val folderContents = parsedLorecipesFolders
            ?: return ImportResult.Error("No parsed data available")

        var importedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var lastImportedId: String? = null

        for ((folderName, files) in folderContents) {
            // Import meal plans automatically (not part of recipe selection)
            if (folderName == MEAL_PLANS_FOLDER) {
                for ((fileName, content) in files) {
                    if (!fileName.endsWith(".json")) continue
                    try {
                        val entries = json.decodeFromString<List<MealPlanEntry>>(content)
                        for (entry in entries) {
                            val existing = mealPlanRepository.getMealPlanByIdOnce(entry.id)
                            if (existing == null) {
                                mealPlanRepository.saveMealPlan(entry)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to import meal plan file $fileName", e)
                    }
                }
                continue
            }

            val jsonContent = files[RecipeSerializer.RECIPE_JSON_FILENAME] ?: continue

            try {
                val recipe = recipeSerializer.deserializeRecipe(jsonContent)

                if (recipe.id !in selectedIds) {
                    continue
                }

                // Check if recipe already exists by ID
                val existing = recipeRepository.getRecipeByIdOnce(recipe.id)
                if (existing != null) {
                    skippedCount++
                    continue
                }

                val localImageUrl = downloadImageIfNeeded(recipe.imageUrl)
                val importedRecipe = recipe.copy(
                    updatedAt = Clock.System.now(),
                    imageUrl = localImageUrl
                )
                val originalHtml = files[RecipeSerializer.RECIPE_HTML_FILENAME]
                recipeRepository.saveRecipe(importedRecipe, originalHtml)
                importedCount++
                lastImportedId = recipe.id
            } catch (e: Exception) {
                failedCount++
            }
        }

        return ImportResult.DirectImportComplete(
            importedCount = importedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            importedRecipeId = lastImportedId
        )
    }
}
