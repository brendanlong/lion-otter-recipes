package com.lionotter.recipes.ui.screens.recipedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val recipeRepository: RecipeRepository,
    settingsDataStore: SettingsDataStore,
    private val calculateIngredientUsage: CalculateIngredientUsageUseCase
) : ViewModel() {

    private val recipeId: String

    init {
        recipeId = savedStateHandle.get<String>("recipeId")
            ?: throw IllegalArgumentException("RecipeDetailViewModel requires a 'recipeId' argument in SavedStateHandle")
    }

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
            initialValue = UnitSystem.CUSTOMARY
        )

    val weightUnitSystem: StateFlow<UnitSystem> = settingsDataStore.weightUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.METRIC
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
     * Key: ingredient name (lowercase)
     * Value: IngredientUsageStatus with total, used, and remaining amounts
     */
    val globalIngredientUsage: StateFlow<Map<String, IngredientUsageStatus>> = combine(
        recipe,
        _usedInstructionIngredients,
        _scale,
        _measurementPreference,
        volumeUnitSystem,
        weightUnitSystem
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val recipe = args[0] as Recipe?
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
     * Deletes the current recipe and emits an event when complete.
     */
    fun deleteRecipe() {
        viewModelScope.launch {
            recipeRepository.deleteRecipe(recipeId)
            _recipeDeleted.emit(Unit)
        }
    }
}
