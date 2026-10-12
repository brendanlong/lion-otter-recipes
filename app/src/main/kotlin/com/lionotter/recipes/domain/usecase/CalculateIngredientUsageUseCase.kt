package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.AmountAccumulator
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
 *
 * The remaining amount is the sum of the occurrences in steps that are not checked off, so
 * amounts in incompatible units (e.g. "1 cup" and "50 g" with no density) are never
 * subtracted from each other. An ingredient is fully used once every step using it is checked,
 * which also works for amount-less ingredients like "salt to taste".
 *
 * Totals and usage are tracked per instruction section so that checking off
 * an ingredient in one section does not affect another section's remaining
 * amounts.
 */
class CalculateIngredientUsageUseCase @Inject constructor() {

    private class UsageTally {
        val total = AmountAccumulator()
        val remaining = AmountAccumulator()
        val used = AmountAccumulator()
        var occurrences = 0
        var usedOccurrences = 0
    }

    fun execute(
        recipe: Recipe,
        usedInstructionIngredients: Set<InstructionIngredientKey>,
        scale: Double,
        measurementPreference: MeasurementPreference,
        volumeSystem: UnitSystem = UnitSystem.localeDefault(),
        weightSystem: UnitSystem = UnitSystem.localeDefault()
    ): Map<String?, Map<String, IngredientUsageStatus>> {
        val sectionTallies = linkedMapOf<String?, MutableMap<String, UsageTally>>()
        recipe.instructionSections.forEachIndexed { sectionIndex, section ->
            val tallies = sectionTallies.getOrPut(section.name) { linkedMapOf() }
            section.steps.forEachIndexed { stepIndex, step ->
                val multiplier = step.yields * scale
                step.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                    val isUsed = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex) in
                        usedInstructionIngredients
                    (listOf(ingredient) + ingredient.alternates).forEach { item ->
                        addOccurrence(tallies, item, isUsed, multiplier, measurementPreference, volumeSystem, weightSystem)
                    }
                }
            }
        }

        return sectionTallies.mapValues { (_, tallies) ->
            tallies.mapValues { (_, tally) -> tally.toStatus(volumeSystem, weightSystem) }
        }
    }

    private fun addOccurrence(
        tallies: MutableMap<String, UsageTally>,
        ingredient: Ingredient,
        isUsed: Boolean,
        multiplier: Double,
        preference: MeasurementPreference,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ) {
        val tally = tallies.getOrPut(ingredient.name.lowercase()) { UsageTally() }
        // Apply the measurement preference per occurrence so totals match what the user sees
        val displayAmount = ingredient.getDisplayAmount(
            scale = 1.0,
            preference = preference,
            volumeSystem = volumeSystem,
            weightSystem = weightSystem
        )
        tally.occurrences++
        tally.total.add(displayAmount, ingredient.density, multiplier)
        if (isUsed) {
            tally.usedOccurrences++
            tally.used.add(displayAmount, ingredient.density, multiplier)
        } else {
            tally.remaining.add(displayAmount, ingredient.density, multiplier)
        }
    }

    private fun UsageTally.toStatus(volumeSystem: UnitSystem, weightSystem: UnitSystem): IngredientUsageStatus {
        val totalAmounts = total.toDisplayAmounts(volumeSystem, weightSystem)
        val remainingAmounts = remaining.toDisplayAmounts(volumeSystem, weightSystem)
        val firstTotal = totalAmounts.firstOrNull()
        val firstRemaining = remainingAmounts.firstOrNull()
        return IngredientUsageStatus(
            totalAmount = firstTotal?.value,
            usedAmount = used.rawTotal(),
            unit = firstTotal?.unit,
            isFullyUsed = occurrences > 0 && usedOccurrences == occurrences,
            remainingAmount = firstRemaining?.value,
            remainingUnit = firstRemaining?.unit ?: firstTotal?.unit,
            remainingAmounts = remainingAmounts
        )
    }
}
