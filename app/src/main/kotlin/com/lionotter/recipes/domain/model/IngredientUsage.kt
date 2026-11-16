package com.lionotter.recipes.domain.model


/**
 * Usage of an aggregated ingredient by checked-off instruction steps.
 *
 * @property isFullyUsed every step using the ingredient is checked off
 * @property anyUsed at least one step using the ingredient is checked off
 * @property remaining the amounts in steps not yet checked off. Amounts that can't be combined
 * (e.g. "1 cup" and "50 g" with no density) are listed separately; format with [formatAmounts]
 * to show e.g. "1/2 cup + 50 g".
 */
data class IngredientUsageStatus(
    val isFullyUsed: Boolean,
    val anyUsed: Boolean,
    val remaining: List<Amount>
)

/**
 * Key to uniquely identify an ingredient in an instruction step.
 * Format: "sectionIndex-stepIndex-ingredientIndex"
 */
typealias InstructionIngredientKey = String

fun createInstructionIngredientKey(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int): InstructionIngredientKey =
    "$sectionIndex-$stepIndex-$ingredientIndex"
