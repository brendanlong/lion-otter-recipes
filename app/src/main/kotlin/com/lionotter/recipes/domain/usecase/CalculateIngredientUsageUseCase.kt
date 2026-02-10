package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import javax.inject.Inject

/**
 * Calculates ingredient usage status by comparing aggregated ingredient totals
 * (derived from steps) against amounts used in checked instruction steps.
 */
class CalculateIngredientUsageUseCase @Inject constructor() {

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

        return globalTotals.mapValues { (key, totalPair) ->
            val (total, unit) = totalPair
            val used = usedAmounts[key] ?: 0.0
            val remaining = total?.let { it - used }
            val isFullyUsed = when {
                total == null -> used > 0
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
    }

    /**
     * Build global totals by aggregating ingredients from all steps.
     * This mirrors Recipe.aggregateIngredients() but returns raw amounts for usage tracking.
     */
    private fun buildGlobalTotals(
        recipe: Recipe,
        scale: Double,
        preference: MeasurementPreference,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): Map<String, Pair<Double?, String?>> {
        val globalTotals = mutableMapOf<String, Pair<Double?, String?>>()
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
        totals: MutableMap<String, Pair<Double?, String?>>,
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

        val existing = totals[key]
        if (existing != null) {
            val existingTotal = existing.first
            val newTotal = when {
                existingTotal == null || totalValue == null -> null
                else -> existingTotal + totalValue
            }
            totals[key] = Pair(newTotal, existing.second ?: unit)
        } else {
            totals[key] = Pair(totalValue, unit)
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
        usedAmounts[key] = (usedAmounts[key] ?: 0.0) + amount
    }
}
