package com.lionotter.recipes.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents the usage status of a global ingredient based on instruction step usage.
 *
 * [totalAmount]/[unit] and [remainingAmount]/[remainingUnit] describe the first kind of amount.
 * When an ingredient's amounts can't be combined (e.g. "1 cup" in one step and "50 g" in another
 * with no density), [remainingAmounts] lists every remaining amount; format it with
 * [formatAmounts] to show e.g. "1/2 cup + 50 g".
 */
@Immutable
data class IngredientUsageStatus(
    val totalAmount: Double?,
    val usedAmount: Double,
    val unit: String?,
    val isFullyUsed: Boolean,
    val remainingAmount: Double?,
    val remainingUnit: String? = unit,
    val remainingAmounts: List<Amount> = listOfNotNull(remainingAmount?.let { Amount(it, remainingUnit) })
)

/**
 * Key to uniquely identify an ingredient in an instruction step.
 * Format: "sectionIndex-stepIndex-ingredientIndex"
 */
typealias InstructionIngredientKey = String

fun createInstructionIngredientKey(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int): InstructionIngredientKey =
    "$sectionIndex-$stepIndex-$ingredientIndex"
