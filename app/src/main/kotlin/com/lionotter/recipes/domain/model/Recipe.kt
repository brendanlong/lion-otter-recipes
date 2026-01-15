package com.lionotter.recipes.domain.model

import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val sourceUrl: String? = null,
    val story: String? = null,
    val servings: Int? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val ingredientSections: List<IngredientSection> = emptyList(),
    val instructionSections: List<InstructionSection> = emptyList(),
    val tags: List<String> = emptyList(),
    val imageUrl: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class IngredientSection(
    val name: String? = null,
    val ingredients: List<Ingredient> = emptyList()
)

@Serializable
data class Ingredient(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val notes: String? = null
) {
    fun format(scale: Double = 1.0): String {
        val scaledQty = quantity?.let { it * scale }
        return buildString {
            scaledQty?.let {
                append(formatQuantity(it))
                append(" ")
            }
            unit?.let {
                // Use 1 for singular (qty <= 1), 2 for plural (qty > 1)
                val count = if ((scaledQty ?: 1.0) > 1.0) 2 else 1
                // Normalize to singular first, then pluralize based on count
                append(it.singularize().pluralize(count))
                append(" ")
            }
            append(name)
            notes?.let {
                append(", ")
                append(it)
            }
        }
    }

    private fun formatQuantity(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) {
            qty.toLong().toString()
        } else {
            // Convert to fractions for common values
            val fractions = mapOf(
                0.25 to "1/4",
                0.33 to "1/3",
                0.5 to "1/2",
                0.66 to "2/3",
                0.75 to "3/4"
            )
            val whole = qty.toLong()
            val decimal = qty - whole

            val fraction = fractions.entries.minByOrNull {
                kotlin.math.abs(it.key - decimal)
            }?.takeIf {
                kotlin.math.abs(it.key - decimal) < 0.05
            }?.value

            when {
                fraction != null && whole > 0 -> "$whole $fraction"
                fraction != null -> fraction
                else -> "%.2f".format(qty).trimEnd('0').trimEnd('.')
            }
        }
    }
}

@Serializable
data class InstructionSection(
    val name: String? = null,
    val steps: List<InstructionStep> = emptyList()
)

@Serializable
data class InstructionStep(
    val stepNumber: Int,
    val instruction: String,
    val ingredientReferences: List<IngredientReference> = emptyList()
)

@Serializable
data class IngredientReference(
    val ingredientName: String,
    val quantity: Double? = null,
    val unit: String? = null
)
