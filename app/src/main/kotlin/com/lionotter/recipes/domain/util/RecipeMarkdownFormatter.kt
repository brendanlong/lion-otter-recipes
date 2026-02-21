package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.formatQuantity

/**
 * Converts a Recipe to a human-readable Markdown format.
 */
object RecipeMarkdownFormatter {

    /**
     * Format a recipe as Markdown, including the title and source URL header.
     */
    fun format(recipe: Recipe): String = buildString {
        // Title
        appendLine("# ${recipe.name}")
        appendLine()

        // Source URL
        recipe.sourceUrl?.let { url ->
            appendLine("*Source: [$url]($url)*")
            appendLine()
        }

        append(formatBody(recipe))
    }

    /**
     * Format a recipe body as Markdown, excluding the title and source URL.
     * Used by the edit screen where title and URL are edited separately.
     */
    fun formatBody(recipe: Recipe): String = buildString {
        // Story/Description
        recipe.story?.let { story ->
            appendLine(story)
            appendLine()
        }

        // Metadata section
        val metadata = buildList {
            recipe.servings?.let { add("**Servings:** $it") }
            recipe.prepTime?.let { add("**Prep Time:** $it") }
            recipe.cookTime?.let { add("**Cook Time:** $it") }
            recipe.totalTime?.let { add("**Total Time:** $it") }
        }
        if (metadata.isNotEmpty()) {
            appendLine(metadata.joinToString(" | "))
            appendLine()
        }

        // Tags
        if (recipe.tags.isNotEmpty()) {
            appendLine("**Tags:** ${recipe.tags.joinToString(", ")}")
            appendLine()
        }

        appendLine("---")
        appendLine()

        // Ingredients section (aggregated from steps)
        appendLine("## Ingredients")
        appendLine()
        val ingredientSections = recipe.aggregateIngredients()
        formatIngredientSections(ingredientSections)
        appendLine()

        // Equipment section
        if (recipe.equipment.isNotEmpty()) {
            appendLine("## Equipment")
            appendLine()
            recipe.equipment.forEach { item ->
                appendLine("- $item")
            }
            appendLine()
        }

        // Instructions section
        appendLine("## Instructions")
        appendLine()
        formatInstructionSections(recipe.instructionSections)

        // User notes section
        recipe.userNotes?.let { notes ->
            appendLine()
            appendLine("## Notes")
            appendLine()
            appendLine(notes)
        }
    }

    private fun StringBuilder.formatIngredientSections(sections: List<IngredientSection>) {
        if (sections.isEmpty()) {
            appendLine("*No ingredients listed*")
            return
        }

        sections.forEach { section ->
            // Section header (if named)
            section.name?.let { name ->
                appendLine("### $name")
                appendLine()
            }

            // Ingredients list
            section.ingredients.forEach { ingredient ->
                appendLine(formatIngredient(ingredient))
            }
            appendLine()
        }
    }

    private fun formatIngredient(ingredient: Ingredient): String = buildString {
        append("- ")

        // Format amount
        ingredient.amount?.let { amount ->
            append("${formatAmount(amount)} ")
        }

        // Ingredient name
        append(ingredient.name)

        // Notes
        ingredient.notes?.let { notes ->
            append(", $notes")
        }

        // Optional marker
        if (ingredient.optional) {
            append(" *(optional)*")
        }

        // Alternates
        if (ingredient.alternates.isNotEmpty()) {
            append(" OR ")
            append(ingredient.alternates.joinToString(" OR ") { alt ->
                buildString {
                    alt.amount?.let { amount ->
                        append("${formatAmount(amount)} ")
                    }
                    append(alt.name)
                    alt.notes?.let { append(", $it") }
                }
            })
        }
    }

    private fun formatAmount(amount: Amount): String {
        val value = amount.value ?: return amount.unit ?: ""
        val formattedValue = formatQuantity(value)
        return if (amount.unit != null) {
            "$formattedValue ${amount.unit}"
        } else {
            formattedValue
        }
    }

    private fun StringBuilder.formatInstructionSections(sections: List<InstructionSection>) {
        if (sections.isEmpty()) {
            appendLine("*No instructions listed*")
            return
        }

        sections.forEach { section ->
            // Section header (if named)
            section.name?.let { name ->
                appendLine("### $name")
                appendLine()
            }

            // Steps
            section.steps.sortedBy { it.stepNumber }.forEach { step ->
                appendLine(formatStep(step))
                appendLine()
            }
        }
    }

    private fun formatStep(step: InstructionStep): String = buildString {
        append("${step.stepNumber}. ")
        append(step.instruction)

        if (step.optional) {
            append(" *(optional)*")
        }
    }

    /**
     * Default ingredient density reference table (g/mL).
     * These are common baking/cooking densities used by the AI when parsing recipes.
     */
    val DEFAULT_DENSITIES: Map<String, Double> = linkedMapOf(
        "water" to 0.96, "milk" to 0.96, "buttermilk" to 0.96,
        "heavy cream" to 0.96, "yogurt" to 0.96, "sour cream" to 0.96,
        "vegetable oil" to 0.84, "olive oil" to 0.84, "coconut oil" to 0.96, "butter" to 0.96,
        "lard" to 0.96, "vegetable shortening" to 0.78,
        "honey" to 1.42, "molasses" to 1.44, "corn syrup" to 1.32, "maple syrup" to 1.32,
        "all-purpose flour" to 0.51, "bread flour" to 0.51, "cake flour" to 0.51,
        "whole wheat flour" to 0.48,
        "pastry flour" to 0.45, "almond flour" to 0.41, "coconut flour" to 0.54,
        "rye flour" to 0.45,
        "cornmeal" to 0.58, "cornstarch" to 0.47, "cocoa powder" to 0.35,
        "tapioca starch" to 0.48, "potato starch" to 0.64,
        "granulated sugar" to 0.84, "brown sugar (packed)" to 0.90,
        "confectioners sugar" to 0.48,
        "demerara sugar" to 0.93, "turbinado sugar" to 0.76,
        "table salt" to 1.22, "kosher salt (Diamond Crystal)" to 0.54,
        "kosher salt (Morton's)" to 1.08,
        "baking powder" to 0.81, "baking soda" to 1.22,
        "peanut butter" to 1.14, "cream cheese" to 0.96,
        "oats (old-fashioned)" to 0.38, "oats (rolled)" to 0.48,
        "chocolate chips" to 0.72, "walnuts (chopped)" to 0.48, "pecans (chopped)" to 0.48,
        "breadcrumbs (dried)" to 0.47, "panko" to 0.21,
        "vanilla extract" to 0.95, "espresso powder" to 0.47
    )

    /**
     * Collect existing ingredient densities from a recipe as a map of
     * ingredient name (lowercase) to density (g/mL).
     * Used to provide density hints to the AI during editing.
     */
    fun collectDensities(recipe: Recipe): Map<String, Double> {
        val densities = mutableMapOf<String, Double>()
        for (section in recipe.instructionSections) {
            for (step in section.steps) {
                for (ingredient in step.ingredients) {
                    ingredient.density?.let { density ->
                        densities[ingredient.name.lowercase()] = density
                    }
                    for (alt in ingredient.alternates) {
                        alt.density?.let { density ->
                            densities[alt.name.lowercase()] = density
                        }
                    }
                }
            }
        }
        return densities
    }

    /**
     * Format a density map as a text block for the AI system prompt.
     * Groups entries with commas and line breaks for readability.
     */
    fun formatDensityHints(densities: Map<String, Double>): String {
        if (densities.isEmpty()) return ""
        return densities.entries.joinToString(",\n") { (name, density) ->
            "$name $density"
        }
    }
}
