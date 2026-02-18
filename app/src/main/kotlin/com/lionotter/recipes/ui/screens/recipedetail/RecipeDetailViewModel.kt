package com.lionotter.recipes.ui.screens.recipedetail

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import com.lionotter.recipes.domain.usecase.ExportSingleRecipeUseCase
import com.lionotter.recipes.domain.util.RecipeSerializer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Represents the location of a highlighted instruction step.
 */
data class HighlightedInstructionStep(
    val sectionIndex: Int,
    val stepIndex: Int
)

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeRepository: IRecipeRepository,
    private val mealPlanRepository: IMealPlanRepository,
    private val settingsDataStore: SettingsDataStore,
    private val calculateIngredientUsage: CalculateIngredientUsageUseCase,
    private val exportSingleRecipeUseCase: ExportSingleRecipeUseCase,
    private val recipeSerializer: RecipeSerializer,
    @param:ApplicationContext private val applicationContext: Context
) : ViewModel() {

    private val recipeId: String = savedStateHandle.get<String>("recipeId")
        ?: throw IllegalArgumentException("RecipeDetailViewModel requires a 'recipeId' argument in SavedStateHandle")

    private val _recipeDeleted = MutableSharedFlow<Unit>()
    val recipeDeleted: SharedFlow<Unit> = _recipeDeleted.asSharedFlow()

    val recipe: StateFlow<Recipe?> = recipeRepository.getRecipeById(recipeId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val keepScreenOn: StateFlow<Boolean> = settingsDataStore.keepScreenOn
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val volumeUnitSystem: StateFlow<UnitSystem> = settingsDataStore.volumeUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val weightUnitSystem: StateFlow<UnitSystem> = settingsDataStore.weightUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val hasApiKey: StateFlow<Boolean> = settingsDataStore.anthropicApiKey
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _scale = MutableStateFlow(1.0)
    val scale: StateFlow<Double> = _scale.asStateFlow()

    private val _measurementPreference = MutableStateFlow(MeasurementPreference.DEFAULT)
    val measurementPreference: StateFlow<MeasurementPreference> = _measurementPreference.asStateFlow()

    /**
     * Tracks which instruction step ingredients have been marked as used.
     * Key is InstructionIngredientKey (sectionIndex-stepIndex-ingredientIndex).
     */
    private val _usedInstructionIngredients = MutableStateFlow<Set<InstructionIngredientKey>>(emptySet())
    val usedInstructionIngredients: StateFlow<Set<InstructionIngredientKey>> = _usedInstructionIngredients.asStateFlow()

    /**
     * Tracks which instruction step is currently highlighted.
     */
    private val _highlightedInstructionStep = MutableStateFlow<HighlightedInstructionStep?>(null)
    val highlightedInstructionStep: StateFlow<HighlightedInstructionStep?> = _highlightedInstructionStep.asStateFlow()

    /**
     * Returns true if any ingredient in the recipe supports conversion (has density).
     * Used to determine whether to show the measurement toggle.
     */
    val supportsConversion: StateFlow<Boolean> = recipe
        .map { recipe ->
            recipe?.instructionSections?.any { section ->
                section.steps.any { step ->
                    step.ingredients.any { ingredient ->
                        ingredient.supportsConversion()
                    }
                }
            } ?: false
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    fun setScale(scale: Double) {
        _scale.value = scale.coerceIn(0.25, 10.0)
    }

    fun incrementScale() {
        setScale(_scale.value + 0.5)
    }

    fun decrementScale() {
        setScale(_scale.value - 0.5)
    }

    fun setMeasurementPreference(preference: MeasurementPreference) {
        _measurementPreference.value = preference
    }

    /**
     * Toggles the used status of an instruction ingredient.
     * An ingredient and its alternates are treated as one item.
     */
    fun toggleInstructionIngredientUsed(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int) {
        val key = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
        _usedInstructionIngredients.value = if (key in _usedInstructionIngredients.value) {
            _usedInstructionIngredients.value - key
        } else {
            _usedInstructionIngredients.value + key
        }
    }

    /**
     * Checks if an instruction ingredient is marked as used.
     */
    fun isInstructionIngredientUsed(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int): Boolean {
        val key = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
        return key in _usedInstructionIngredients.value
    }

    /**
     * Computes the usage status for all aggregated ingredients based on which instruction
     * ingredients have been marked as used.
     * Outer key: section name (null for unnamed sections)
     * Inner key: ingredient name (lowercase)
     * Value: IngredientUsageStatus with total, used, and remaining amounts
     */
    val ingredientUsageBySection: StateFlow<Map<String?, Map<String, IngredientUsageStatus>>> = combine(
        recipe,
        _usedInstructionIngredients,
        _scale,
        _measurementPreference,
        volumeUnitSystem,
        weightUnitSystem
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val recipe = args[0] as Recipe?
        @Suppress("UNCHECKED_CAST")
        val usedKeys = args[1] as Set<InstructionIngredientKey>
        val scale = args[2] as Double
        val preference = args[3] as MeasurementPreference
        val volSystem = args[4] as UnitSystem
        val wtSystem = args[5] as UnitSystem
        if (recipe == null) return@combine emptyMap()
        calculateIngredientUsage.execute(recipe, usedKeys, scale, preference, volSystem, wtSystem)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    /**
     * Resets all ingredient usage tracking.
     */
    fun resetIngredientUsage() {
        _usedInstructionIngredients.value = emptySet()
    }

    /**
     * Toggles the highlighted instruction step.
     * If the step is already highlighted, clears the highlight.
     * Otherwise, sets this step as highlighted.
     */
    fun toggleHighlightedInstructionStep(sectionIndex: Int, stepIndex: Int) {
        val newStep = HighlightedInstructionStep(sectionIndex, stepIndex)
        _highlightedInstructionStep.value = if (_highlightedInstructionStep.value == newStep) {
            null
        } else {
            newStep
        }
    }

    /**
     * Toggles the favorite status of the current recipe.
     */
    fun toggleFavorite() {
        viewModelScope.launch {
            val currentRecipe = recipe.value ?: return@launch
            recipeRepository.setFavorite(recipeId, !currentRecipe.isFavorite)
        }
    }

    /**
     * Returns the number of meal plan entries that reference this recipe.
     */
    suspend fun getAffectedMealPlanCount(): Int {
        return mealPlanRepository.countMealPlansByRecipeId(recipeId)
    }

    /**
     * Deletes the current recipe and any meal plan entries that reference it,
     * then emits an event when complete.
     */
    fun deleteRecipe() {
        viewModelScope.launch {
            mealPlanRepository.deleteMealPlansByRecipeId(recipeId)
            recipeRepository.deleteRecipe(recipeId)
            _recipeDeleted.emit(Unit)
        }
    }

    // --- User Notes ---

    /**
     * Saves user notes for the current recipe.
     * Pass null or empty string to clear notes.
     */
    fun saveUserNotes(notes: String?) {
        viewModelScope.launch {
            val normalizedNotes = notes?.takeIf { it.isNotBlank() }
            recipeRepository.setUserNotes(recipeId, normalizedNotes)
        }
    }

    // --- Recipe Export ---

    private val _exportedFileUri = MutableSharedFlow<Uri>()
    val exportedFileUri: SharedFlow<Uri> = _exportedFileUri.asSharedFlow()

    /**
     * Exports the current recipe to a .lorecipes file in the cache directory
     * and emits the content URI for sharing.
     */
    fun exportRecipeFile() {
        viewModelScope.launch {
            val currentRecipe = recipe.value ?: return@launch

            val sharedDir = File(applicationContext.cacheDir, "shared_recipes").apply { mkdirs() }
            val fileName = "${recipeSerializer.sanitizeFolderName(currentRecipe.name)}.lorecipes"
            val file = File(sharedDir, fileName)

            val result = file.outputStream().use { outputStream ->
                exportSingleRecipeUseCase.exportRecipe(currentRecipe, outputStream)
            }

            if (result is ExportSingleRecipeUseCase.ExportResult.Success) {
                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.fileprovider",
                    file
                )
                _exportedFileUri.emit(uri)
            }
        }
    }

}
