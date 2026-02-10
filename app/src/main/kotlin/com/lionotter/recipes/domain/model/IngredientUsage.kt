package com.lionotter.recipes.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents the usage status of a global ingredient based on instruction step usage.
 */
@Immutable
data class IngredientUsageStatus(
    val totalAmount: Double?,
    val usedAmount: Double,
    val unit: String?,
    val isFullyUsed: Boolean,
    val remainingAmount: Double?,
    val remainingUnit: String? = unit
)

/**
 * Key to uniquely identify an ingredient in an instruction step.
 * Format: "sectionIndex-stepIndex-ingredientIndex"
 */
typealias InstructionIngredientKey = String

fun createInstructionIngredientKey(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int): InstructionIngredientKey =
    "$sectionIndex-$stepIndex-$ingredientIndex"
