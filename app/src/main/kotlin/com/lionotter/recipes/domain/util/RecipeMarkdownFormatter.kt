package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.formatAmount

/**
 * Converts a Recipe to a human-readable Markdown format.
 *
 * Two layouts are produced:
 * - [format] (sharing/export): an aggregated ingredient list followed by the steps.
 * - [formatBody] (AI edit screen): no aggregated list; instead every step lists the ingredients
 *   it uses with their amounts, so re-parsing keeps the per-step assignment, amounts split
 *   across steps, and step repetitions ("yields").
 */
object RecipeMarkdownFormatter {

    /**
     * Format a recipe as Markdown, including the title and source URL header.
     */
    fun format(recipe: Recipe): String = buildString {
        appendHeader(recipe.name, recipe.sourceUrl)
        append(formatBody(recipe, forEditing = false))
    }

    /**
     * Format a recipe body as Markdown for editing, excluding the title, source URL and tags
     * (which the edit screen edits separately). Ingredients are listed under the step that
     * uses them rather than in an aggregated list.
     */
    fun formatBody(recipe: Recipe): String = formatBody(recipe, forEditing = true)

    /**
     * The Markdown sent to the AI for an edit: the separately edited [title], [sourceUrl] and
     * [tags] put back in front of the edited [body] (a [formatBody] layout).
     */
    fun formatForAiEdit(title: String, sourceUrl: String?, tags: List<String>, body: String): String = buildString {
        appendHeader(title, sourceUrl)
        appendTags(tags)
        append(body)
    }

    private fun StringBuilder.appendHeader(title: String, sourceUrl: String?) {
        if (title.isNotBlank()) {
            appendLine("# $title")
            appendLine()
        }
        if (!sourceUrl.isNullOrBlank()) {
            appendLine("*Source: [$sourceUrl]($sourceUrl)*")
            appendLine()
        }
    }

    private fun StringBuilder.appendTags(tags: List<String>) {
        if (tags.isNotEmpty()) {
            appendLine("**Tags:** ${tags.joinToString(", ")}")
            appendLine()
        }
    }

    private fun formatBody(recipe: Recipe, forEditing: Boolean): String = buildString {
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

        if (!forEditing) appendTags(recipe.tags)

        appendLine("---")
        appendLine()

        if (!forEditing) {
            // Ingredients section (aggregated from steps)
            appendLine("## Ingredients")
            appendLine()
            formatIngredientSections(recipe.aggregateIngredients())
            appendLine()
        }

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
        formatInstructionSections(recipe.instructionSections, includeIngredients = forEditing)

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
                appendLine("- ${formatIngredient(ingredient)}")
            }
            appendLine()
        }
    }

    private fun formatIngredient(ingredient: Ingredient): String = buildString {
        // Format amount
        ingredient.amount?.let { amount ->
            formatAmount(amount)?.let { append("$it ") }
        }

        // Name, modifier and notes
        append(ingredient.describe())

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
                        formatAmount(amount)?.let { append("$it ") }
                    }
                    append(alt.describe())
                }
            })
        }
    }

    private fun formatAmount(amount: Amount): String? {
        val value = amount.value ?: return amount.unit
        return formatAmount(value, amount.unit)
    }

    private fun StringBuilder.formatInstructionSections(
        sections: List<InstructionSection>,
        includeIngredients: Boolean
    ) {
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
                appendLine(formatStep(step, includeIngredients))
                if (includeIngredients) {
                    step.ingredients.forEach { ingredient ->
                        appendLine("   - ${formatIngredient(ingredient)}")
                    }
                }
                appendLine()
            }
        }
    }

    private fun formatStep(step: InstructionStep, includeIngredients: Boolean): String = buildString {
        append("${step.stepNumber}. ")
        append(step.instruction)

        if (step.yields > 1) {
            if (includeIngredients && step.ingredients.isNotEmpty()) {
                append(" *(repeat ${step.yields} times; ingredient amounts below are for each time)*")
            } else {
                append(" *(repeat ${step.yields} times)*")
            }
        }

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
