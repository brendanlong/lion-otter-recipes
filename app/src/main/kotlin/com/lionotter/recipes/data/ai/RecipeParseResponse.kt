package com.lionotter.recipes.data.ai

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
    val recipe: RecipeParseResult? = null
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
        instructionSections = instructionSections,
        equipment = equipment,
        tags = tags
    )
}
