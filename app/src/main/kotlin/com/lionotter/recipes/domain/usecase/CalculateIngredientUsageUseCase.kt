package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import javax.inject.Inject

/**
 * Calculates ingredient usage status by comparing global ingredient totals
 * against amounts used in checked instruction steps.
 */
class CalculateIngredientUsageUseCase @Inject constructor() {

    fun execute(
        recipe: Recipe,
        usedInstructionIngredients: Set<InstructionIngredientKey>,
        scale: Double,
        measurementPreference: MeasurementPreference
    ): Map<String, IngredientUsageStatus> {
        val globalTotals = buildGlobalTotals(recipe, scale, measurementPreference)
        val usedAmounts = buildUsedAmounts(recipe, usedInstructionIngredients, scale, measurementPreference)

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

    private fun buildGlobalTotals(
        recipe: Recipe,
        scale: Double,
        preference: MeasurementPreference
    ): Map<String, Pair<Double?, String?>> {
        val globalTotals = mutableMapOf<String, Pair<Double?, String?>>()
        recipe.ingredientSections.forEach { section ->
            section.ingredients.forEach { ingredient ->
                addIngredientTotal(ingredient, globalTotals, scale, preference)
                ingredient.alternates.forEach { alt ->
                    addIngredientTotal(alt, globalTotals, scale, preference)
                }
            }
        }
        return globalTotals
    }

    private fun addIngredientTotal(
        ingredient: Ingredient,
        totals: MutableMap<String, Pair<Double?, String?>>,
        scale: Double,
        preference: MeasurementPreference
    ) {
        val key = ingredient.name.lowercase()
        val measurement = ingredient.getPreferredMeasurement(preference)
        val value = measurement?.value?.let { it * scale }
        totals[key] = Pair(value, measurement?.unit)
    }

    private fun buildUsedAmounts(
        recipe: Recipe,
        usedKeys: Set<InstructionIngredientKey>,
        scale: Double,
        preference: MeasurementPreference
    ): Map<String, Double> {
        val usedAmounts = mutableMapOf<String, Double>()
        recipe.instructionSections.forEachIndexed { sectionIndex, section ->
            section.steps.forEachIndexed { stepIndex, step ->
                step.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                    val stepKey = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
                    if (stepKey in usedKeys) {
                        addIngredientUsage(ingredient, usedAmounts, scale, preference)
                        ingredient.alternates.forEach { alt ->
                            addIngredientUsage(alt, usedAmounts, scale, preference)
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
        preference: MeasurementPreference
    ) {
        val key = ingredient.name.lowercase()
        val measurement = ingredient.getPreferredMeasurement(preference)
        val amount = measurement?.value?.let { it * scale } ?: 0.0
        usedAmounts[key] = (usedAmounts[key] ?: 0.0) + amount
    }
}
