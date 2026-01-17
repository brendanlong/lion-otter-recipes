package com.lionotter.recipes.ui.screens.recipedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.MeasurementType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.GetRecipeByIdUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Represents the usage status of a global ingredient based on instruction step usage.
 */
data class IngredientUsageStatus(
    val totalAmount: Double?,
    val usedAmount: Double,
    val unit: String?,
    val isFullyUsed: Boolean,
    val remainingAmount: Double?
)

/**
 * Key to uniquely identify an ingredient in an instruction step.
 * Format: "sectionIndex-stepIndex-ingredientIndex"
 */
typealias InstructionIngredientKey = String

fun createInstructionIngredientKey(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int): InstructionIngredientKey =
    "$sectionIndex-$stepIndex-$ingredientIndex"

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
    getRecipeByIdUseCase: GetRecipeByIdUseCase
) : ViewModel() {

    private val recipeId: String = savedStateHandle.get<String>("recipeId")
        ?: throw IllegalArgumentException("Recipe ID is required")

    val recipe: StateFlow<Recipe?> = getRecipeByIdUseCase.execute(recipeId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _scale = MutableStateFlow(1.0)
    val scale: StateFlow<Double> = _scale.asStateFlow()

    private val _measurementPreference = MutableStateFlow(MeasurementPreference.ORIGINAL)
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
     * Returns true if the recipe has ingredients with multiple measurement types available.
     * This is used to determine whether to show the measurement toggle.
     */
    val hasMultipleMeasurementTypes: StateFlow<Boolean> = recipe
        .map { recipe ->
            recipe?.ingredientSections?.any { section ->
                section.ingredients.any { ingredient ->
                    ingredient.hasMultipleMeasurementTypes()
                }
            } ?: false
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /**
     * Returns the set of all measurement types available in the recipe.
     */
    val availableMeasurementTypes: StateFlow<Set<MeasurementType>> = recipe
        .map { recipe ->
            recipe?.ingredientSections?.flatMap { section ->
                section.ingredients.flatMap { ingredient ->
                    ingredient.availableMeasurementTypes()
                }
            }?.toSet() ?: emptySet()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
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
     * Computes the usage status for all global ingredients based on which instruction
     * ingredients have been marked as used.
     * Key: ingredient name (lowercase)
     * Value: IngredientUsageStatus with total, used, and remaining amounts
     */
    val globalIngredientUsage: StateFlow<Map<String, IngredientUsageStatus>> = combine(
        recipe,
        _usedInstructionIngredients,
        _scale,
        _measurementPreference
    ) { recipe, usedKeys, scale, preference ->
        if (recipe == null) return@combine emptyMap()

        // Build a map of global ingredient totals
        val globalTotals = mutableMapOf<String, Pair<Double?, String?>>()
        recipe.ingredientSections.forEach { section ->
            section.ingredients.forEach { ingredient ->
                val key = ingredient.name.lowercase()
                val measurement = ingredient.getPreferredMeasurement(preference)
                val value = measurement?.value?.let { it * scale }
                globalTotals[key] = Pair(value, measurement?.unit)

                // Also include alternates as separate entries
                ingredient.alternates.forEach { alt ->
                    val altKey = alt.name.lowercase()
                    val altMeasurement = alt.getPreferredMeasurement(preference)
                    val altValue = altMeasurement?.value?.let { it * scale }
                    globalTotals[altKey] = Pair(altValue, altMeasurement?.unit)
                }
            }
        }

        // Sum used amounts from instruction ingredients
        val usedAmounts = mutableMapOf<String, Double>()
        recipe.instructionSections.forEachIndexed { sectionIndex, section ->
            section.steps.forEachIndexed { stepIndex, step ->
                step.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                    val stepKey = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
                    if (stepKey in usedKeys) {
                        // Add this ingredient's amount to used total
                        addIngredientUsage(ingredient, usedAmounts, scale, preference)
                        // Also add all alternates
                        ingredient.alternates.forEach { alt ->
                            addIngredientUsage(alt, usedAmounts, scale, preference)
                        }
                    }
                }
            }
        }

        // Build the result map
        globalTotals.mapValues { (key, totalPair) ->
            val (total, unit) = totalPair
            val used = usedAmounts[key] ?: 0.0
            val remaining = total?.let { it - used }
            val isFullyUsed = when {
                total == null -> used > 0 // For count-less ingredients, any usage means fully used
                total <= 0 -> used > 0
                else -> used >= total
            }
            IngredientUsageStatus(
                totalAmount = total,
                usedAmount = used,
                unit = unit,
                isFullyUsed = isFullyUsed,
                remainingAmount = remaining?.coerceAtLeast(0.0)
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    private fun addIngredientUsage(
        ingredient: Ingredient,
        usedAmounts: MutableMap<String, Double>,
        scale: Double,
        preference: MeasurementPreference
    ) {
        val key = ingredient.name.lowercase()
        val measurement = ingredient.getPreferredMeasurement(preference)
        val amount = measurement?.value?.let { it * scale } ?: 0.0
        usedAmounts[key] = (usedAmounts[key] ?: 0.0) + amount
    }

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
}
