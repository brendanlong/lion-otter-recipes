package com.lionotter.recipes.domain.model

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
