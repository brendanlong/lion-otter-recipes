package com.lionotter.recipes.domain.model

import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the type of measurement for an ingredient amount.
 */
@Serializable
enum class MeasurementType {
    @SerialName("volume")
    VOLUME,
    @SerialName("weight")
    WEIGHT,
    @SerialName("count")
    COUNT
}

/**
 * Represents a single measurement amount with its unit and type.
 */
@Serializable
data class Measurement(
    val value: Double,
    val unit: String,
    val type: MeasurementType,
    val isDefault: Boolean = false
)

/**
 * User preference for how measurements should be displayed.
 */
enum class MeasurementPreference {
    ORIGINAL,  // Show the default measurement from the recipe
    VOLUME,    // Prefer volume measurements
    WEIGHT     // Prefer weight measurements
}

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
    val notes: String? = null,
    val alternates: List<Ingredient> = emptyList(),
    val amounts: List<Measurement> = emptyList()
) {
    /**
     * Returns the measurement to display based on user preference.
     * Falls back to original measurement if preferred type is not available.
     */
    fun getPreferredMeasurement(preference: MeasurementPreference): Measurement? {
        if (amounts.isEmpty()) return null

        return when (preference) {
            MeasurementPreference.ORIGINAL -> amounts.find { it.isDefault } ?: amounts.firstOrNull()
            MeasurementPreference.VOLUME -> amounts.find { it.type == MeasurementType.VOLUME }
                ?: amounts.find { it.isDefault } ?: amounts.firstOrNull()
            MeasurementPreference.WEIGHT -> amounts.find { it.type == MeasurementType.WEIGHT }
                ?: amounts.find { it.isDefault } ?: amounts.firstOrNull()
        }
    }

    /**
     * Returns true if this ingredient has multiple measurement types available.
     */
    fun hasMultipleMeasurementTypes(): Boolean {
        return amounts.map { it.type }.distinct().size > 1
    }

    /**
     * Returns the available measurement types for this ingredient.
     */
    fun availableMeasurementTypes(): Set<MeasurementType> {
        return amounts.map { it.type }.toSet()
    }

    /**
     * Format the ingredient for display.
     */
    fun format(scale: Double = 1.0, preference: MeasurementPreference = MeasurementPreference.ORIGINAL): String {
        val measurement = getPreferredMeasurement(preference)
            ?: return name + (notes?.let { ", $it" } ?: "")

        val scaledQty = measurement.value * scale
        return buildString {
            append(formatQuantity(scaledQty))
            append(" ")
            // Use 1 for singular (qty <= 1), 2 for plural (qty > 1)
            val count = if (scaledQty > 1.0) 2 else 1
            // Normalize to singular first, then pluralize based on count
            append(measurement.unit.singularize().pluralize(count))
            append(" ")
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
    val ingredientReferences: List<IngredientReference> = emptyList(),
    val ingredients: List<Ingredient> = emptyList()
)

@Serializable
data class IngredientReference(
    val ingredientName: String,
    val quantity: Double? = null,
    val unit: String? = null
)
