package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitCategory
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.model.fromBaseUnit
import com.lionotter.recipes.domain.model.toBaseUnitValue
import com.lionotter.recipes.domain.model.unitType
import javax.inject.Inject

/**
 * Calculates ingredient usage status by comparing aggregated ingredient totals
 * (derived from steps) against amounts used in checked instruction steps.
 *
 * All amounts are normalized to base units (grams for weight, mL for volume)
 * before summing to avoid unit mismatch errors when ingredients across steps
 * use different units (e.g. cups in one step and tbsp in another).
 */
class CalculateIngredientUsageUseCase @Inject constructor() {

    /**
     * Tracks accumulated base-unit value and the unit category for an ingredient.
     */
    private data class BaseUnitAccumulator(
        var baseValue: Double? = null,
        val category: UnitCategory? = null
    ) {
        fun add(value: Double?) {
            baseValue = when {
                baseValue == null || value == null -> null
                else -> baseValue!! + value
            }
        }
    }

    fun execute(
        recipe: Recipe,
        usedInstructionIngredients: Set<InstructionIngredientKey>,
        scale: Double,
        measurementPreference: MeasurementPreference,
        volumeSystem: UnitSystem = UnitSystem.CUSTOMARY,
        weightSystem: UnitSystem = UnitSystem.METRIC
    ): Map<String, IngredientUsageStatus> {
        val globalTotals = buildGlobalTotals(recipe, scale, measurementPreference, volumeSystem, weightSystem)
        val usedAmounts = buildUsedAmounts(recipe, usedInstructionIngredients, scale, measurementPreference, volumeSystem, weightSystem)

        return globalTotals.mapValues { (key, accumulator) ->
            val totalBase = accumulator.baseValue
            val usedBase = usedAmounts[key] ?: 0.0
            val remainingBase = totalBase?.let { (it - usedBase).coerceAtLeast(0.0) }
            val isFullyUsed = when {
                totalBase == null -> usedBase > 0
                totalBase <= 0 -> usedBase > 0
                else -> usedBase >= totalBase
            }

            // Convert base unit values back to display units
            val category = accumulator.category
            val totalDisplay = if (totalBase != null && category != null) {
                fromBaseUnit(totalBase, category, volumeSystem, weightSystem)
            } else {
                null
            }
            val remainingDisplay = if (remainingBase != null && category != null) {
                fromBaseUnit(remainingBase, category, volumeSystem, weightSystem)
            } else {
                null
            }

            IngredientUsageStatus(
                totalAmount = totalDisplay?.value,
                usedAmount = usedBase,
                unit = totalDisplay?.unit,
                isFullyUsed = isFullyUsed,
                remainingAmount = remainingDisplay?.value,
                remainingUnit = remainingDisplay?.unit ?: totalDisplay?.unit
            )
        }
    }

    /**
     * Build global totals by aggregating ingredients from all steps in base units.
     */
    private fun buildGlobalTotals(
        recipe: Recipe,
        scale: Double,
        preference: MeasurementPreference,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): Map<String, BaseUnitAccumulator> {
        val globalTotals = mutableMapOf<String, BaseUnitAccumulator>()
        recipe.instructionSections.forEach { section ->
            section.steps.forEach { step ->
                val yields = step.yields
                step.ingredients.forEach { ingredient ->
                    addIngredientTotal(ingredient, globalTotals, scale, preference, yields, volumeSystem, weightSystem)
                    ingredient.alternates.forEach { alt ->
                        addIngredientTotal(alt, globalTotals, scale, preference, yields, volumeSystem, weightSystem)
                    }
                }
            }
        }
        return globalTotals
    }

    private fun addIngredientTotal(
        ingredient: Ingredient,
        totals: MutableMap<String, BaseUnitAccumulator>,
        scale: Double,
        preference: MeasurementPreference,
        yields: Int,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ) {
        val key = ingredient.name.lowercase()
        val displayAmount = ingredient.getDisplayAmount(scale = 1.0, preference = preference, volumeSystem = volumeSystem, weightSystem = weightSystem)
        val perIterationValue = displayAmount?.value
        val totalValue = perIterationValue?.let { it * yields * scale }
        val unit = displayAmount?.unit

        // Convert to base units for accurate summation
        val baseValue = if (totalValue != null && unit != null) {
            toBaseUnitValue(totalValue, unit)
        } else {
            totalValue // count items or null
        }
        val category = unit?.let { unitType(it) }

        val existing = totals[key]
        if (existing != null) {
            existing.add(baseValue)
        } else {
            totals[key] = BaseUnitAccumulator(baseValue = baseValue, category = category)
        }
    }

    private fun buildUsedAmounts(
        recipe: Recipe,
        usedKeys: Set<InstructionIngredientKey>,
        scale: Double,
        preference: MeasurementPreference,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): Map<String, Double> {
        val usedAmounts = mutableMapOf<String, Double>()
        recipe.instructionSections.forEachIndexed { sectionIndex, section ->
            section.steps.forEachIndexed { stepIndex, step ->
                val yields = step.yields
                step.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                    val stepKey = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
                    if (stepKey in usedKeys) {
                        addIngredientUsage(ingredient, usedAmounts, scale, preference, yields, volumeSystem, weightSystem)
                        ingredient.alternates.forEach { alt ->
                            addIngredientUsage(alt, usedAmounts, scale, preference, yields, volumeSystem, weightSystem)
                        }
                    }
                }
            }
        }
        return usedAmounts
    }

    private fun addIngredientUsage(
        ingredient: Ingredient,
        usedAmounts: MutableMap<String, Double>,
        scale: Double,
        preference: MeasurementPreference,
        yields: Int,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ) {
        val key = ingredient.name.lowercase()
        val displayAmount = ingredient.getDisplayAmount(scale = 1.0, preference = preference, volumeSystem = volumeSystem, weightSystem = weightSystem)
        val perIterationValue = displayAmount?.value ?: 0.0
        val amount = perIterationValue * yields * scale
        val unit = displayAmount?.unit

        // Convert to base units for accurate summation
        val baseAmount = if (unit != null) {
            toBaseUnitValue(amount, unit) ?: amount
        } else {
            amount
        }

        usedAmounts[key] = (usedAmounts[key] ?: 0.0) + baseAmount
    }
}
