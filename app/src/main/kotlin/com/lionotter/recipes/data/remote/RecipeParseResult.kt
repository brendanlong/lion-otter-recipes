package com.lionotter.recipes.data.remote

import com.lionotter.recipes.domain.model.InstructionSection
import kotlinx.serialization.Serializable

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
)
