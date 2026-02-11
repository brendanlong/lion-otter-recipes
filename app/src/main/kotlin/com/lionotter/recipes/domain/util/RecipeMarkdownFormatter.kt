package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe

/**
 * Converts a Recipe to a human-readable Markdown format.
 */
object RecipeMarkdownFormatter {

    fun format(recipe: Recipe): String = buildString {
        // Title
        appendLine("# ${recipe.name}")
        appendLine()

        // Source URL
        recipe.sourceUrl?.let { url ->
            appendLine("*Source: [$url]($url)*")
            appendLine()
        }

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
}
