package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.port.ParsedRecipe
import kotlinx.serialization.Serializable

/**
 * Wrapper response for recipe parsing that allows the AI to indicate
 * success with parsed data or failure with a human-readable error message.
 */
@Serializable
data class RecipeParseResponse(
    val success: Boolean,
    val error: String? = null,
    val recipe: RecipeParseResult? = null,
    /** A prompt for a picture of the dish; "" unless the request asked for one. */
    val imagePrompt: String = ""
)

@Serializable
data class RecipeParseResult(
    val name: String,
    val story: String? = null,
    val servings: Int? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val instructionSections: List<InstructionSection>,
    val equipment: List<String> = emptyList(),
    val tags: List<String>,
    val imageUrl: String? = null
) {
    fun toParsedRecipe() = ParsedRecipe(
        name = name,
        story = story,
        servings = servings,
        prepTime = prepTime,
        cookTime = cookTime,
        totalTime = totalTime,
        instructionSections = instructionSections.map { section ->
            section.copy(
                steps = section.steps.map { step ->
                    step.copy(ingredients = step.ingredients.map { it.withBlankModifiersRemoved() })
                }
            )
        },
        equipment = equipment,
        tags = tags
    )
}

/** The schema requires a modifier string, with "" meaning none; the app stores none as null. */
private fun Ingredient.withBlankModifiersRemoved(): Ingredient = copy(
    modifier = modifier?.trim()?.takeIf { it.isNotEmpty() },
    alternates = alternates.map { it.withBlankModifiersRemoved() }
)
