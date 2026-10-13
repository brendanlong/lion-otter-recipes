package com.lionotter.recipes.domain.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * User preference for how measurements should be displayed.
 */
enum class MeasurementPreference {
    DEFAULT,   // Show the measurement as provided by the recipe
    VOLUME,    // Convert to volume using density
    WEIGHT     // Convert to weight using density
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
    val instructionSections: List<InstructionSection> = emptyList(),
    val equipment: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val imageUrl: String? = null,
    val sourceImageUrl: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isFavorite: Boolean = false,
    val userNotes: String? = null,
    /** The AI's prompt for generating a picture of the dish; null for recipes parsed before prompts were kept. */
    val imagePrompt: String? = null
) {
    /**
     * This recipe with the AI-generated content of [parsed] (name, story, servings, times,
     * instructions, equipment, tags, image prompt) and [updatedAt] = [now]. Everything else (identity, image,
     * source, favorite, notes, creation time) is kept, so adding a field to [Recipe] keeps it
     * by default when a recipe is edited or regenerated.
     */
    fun withRegeneratedContent(parsed: Recipe, now: Instant): Recipe = copy(
        name = parsed.name,
        story = parsed.story,
        servings = parsed.servings,
        prepTime = parsed.prepTime,
        cookTime = parsed.cookTime,
        totalTime = parsed.totalTime,
        instructionSections = parsed.instructionSections,
        equipment = parsed.equipment,
        tags = parsed.tags,
        imagePrompt = parsed.imagePrompt ?: imagePrompt,
        updatedAt = now
    )

    /**
     * Aggregates all ingredients from all steps across all instruction sections.
     * Combines amounts for the same ingredient name and modifier (case-insensitive, see
     * [Ingredient.aggregationKey]) within each section,
     * respecting the yields multiplier on each step.
     *
     * See [aggregateIngredientOccurrences] for how amounts in different units are combined.
     *
     * Returns grouped by instruction section name for display.
     */
    fun aggregateIngredients(): List<IngredientSection> {
        val sectionOccurrences = linkedMapOf<String?, MutableList<Pair<Ingredient, Int>>>()
        for (section in instructionSections) {
            val occurrences = sectionOccurrences.getOrPut(section.name) { mutableListOf() }
            for (step in section.steps) {
                step.ingredients.forEach { occurrences.add(it to step.yields) }
            }
        }

        return sectionOccurrences.map { (sectionName, occurrences) ->
            IngredientSection(
                name = sectionName,
                ingredients = aggregateIngredientOccurrences(occurrences)
            )
        }
    }

    /**
     * Aggregates all ingredients across every section into a single list, combining
     * the same ingredient used in different sections (e.g. butter in both cake and frosting).
     */
    fun aggregateAllIngredients(): List<Ingredient> =
        aggregateIngredientOccurrences(
            instructionSections.flatMap { section ->
                section.steps.flatMap { step -> step.ingredients.map { it to step.yields } }
            }
        )
}

@Serializable
data class IngredientSection(
    val name: String? = null,
    val ingredients: List<Ingredient> = emptyList()
)

/**
 * A single measurement amount with value and unit.
 * Unit is one of: mg, g, kg, oz, lb (weight), mL, L, tsp, tbsp, cup, fl_oz, pint, quart, gal (volume),
 * or omitted for count items.
 */
@Serializable
data class Amount(
    val value: Double? = null,
    val unit: String? = null
)

/**
 * An ingredient used by a step.
 *
 * @param modifier how the ingredient is prepared ("peeled", "chopped", "melted", "room
 *   temperature"). It is not part of what the ingredient is: the grocery list buys "apple"
 *   whether the recipe peels it or not. Null when there is none, and for recipes parsed before
 *   this field existed, which keep any preparation in [name] or [notes].
 * @param notes any other remark ("divided", "to taste", "plus more for dusting")
 */
@Serializable
data class Ingredient(
    val name: String,
    val notes: String? = null,
    val alternates: List<Ingredient> = emptyList(),
    val amount: Amount? = null,
    val density: Double? = null,
    val optional: Boolean = false,
    val modifier: String? = null
) {
    /**
     * Identifies the same ingredient prepared the same way, for combining amounts within a
     * recipe: "butter, softened" and "butter, melted" are listed separately.
     */
    val aggregationKey: String
        get() {
            val base = name.trim().lowercase()
            val preparation = modifier?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return base
            return "$base, $preparation"
        }

    /** The name followed by the modifier and notes, e.g. "apple, peeled, divided". */
    fun describe(): String =
        (listOf(name) + listOfNotNull(modifier, notes).filter { it.isNotBlank() }).joinToString(", ")

    /**
     * Format the ingredient for display.
     */
    fun format(
        scale: Double = 1.0,
        preference: MeasurementPreference = MeasurementPreference.DEFAULT,
        volumeSystem: UnitSystem = UnitSystem.localeDefault(),
        weightSystem: UnitSystem = UnitSystem.localeDefault()
    ): String {
        val displayAmount = getDisplayAmount(scale, preference, volumeSystem, weightSystem)
        val value = displayAmount?.value ?: return describe()
        return "${formatAmount(value, displayAmount.unit)} ${describe()}"
    }

    /**
     * Get the amount to display based on user preferences.
     * Handles both cross-category conversion (volume↔weight via density) and
     * within-category system conversion (customary↔metric).
     */
    fun getDisplayAmount(
        scale: Double = 1.0,
        preference: MeasurementPreference = MeasurementPreference.DEFAULT,
        volumeSystem: UnitSystem = UnitSystem.localeDefault(),
        weightSystem: UnitSystem = UnitSystem.localeDefault()
    ): Amount? {
        val amt = amount ?: return null
        val scaledValue = amt.value?.let { it * scale }

        // If no unit, return as-is (count items like "3 eggs")
        if (amt.unit == null) {
            return Amount(value = scaledValue, unit = amt.unit)
        }

        // Handle cross-category conversion first (volume↔weight)
        if (preference != MeasurementPreference.DEFAULT && density != null && scaledValue != null) {
            val currentType = unitType(amt.unit)
            val wantType = when (preference) {
                MeasurementPreference.VOLUME -> UnitCategory.VOLUME
                MeasurementPreference.WEIGHT -> UnitCategory.WEIGHT
                MeasurementPreference.DEFAULT -> null
            }

            if (currentType != null && wantType != null && currentType != wantType) {
                return convertAmount(scaledValue, amt.unit, currentType, wantType, density, volumeSystem, weightSystem)
            }
        }

        // Apply within-category unit system conversion
        return convertToSystem(scaledValue, amt.unit, volumeSystem, weightSystem)
    }

    /**
     * Returns true if this ingredient supports unit conversion (has density and a convertible unit).
     */
    fun supportsConversion(): Boolean {
        return density != null && amount?.unit != null && unitType(amount.unit) != null
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
    val ingredients: List<Ingredient> = emptyList(),
    val yields: Int = 1,
    val optional: Boolean = false
)
