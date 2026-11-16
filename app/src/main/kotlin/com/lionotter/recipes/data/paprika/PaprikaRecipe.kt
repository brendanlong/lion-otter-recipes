package com.lionotter.recipes.data.paprika

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model for a single recipe from a Paprika export.
 * Each .paprikarecipe file contains a gzip-compressed JSON blob matching this schema.
 */
@Serializable
data class PaprikaRecipe(
    val uid: String,
    val name: String,
    val description: String? = null,
    val ingredients: String? = null,
    val directions: String? = null,
    val notes: String? = null,
    @SerialName("nutritional_info")
    val nutritionalInfo: String? = null,
    @SerialName("prep_time")
    val prepTime: String? = null,
    @SerialName("cook_time")
    val cookTime: String? = null,
    @SerialName("total_time")
    val totalTime: String? = null,
    val difficulty: String? = null,
    val servings: String? = null,
    val source: String? = null,
    @SerialName("source_url")
    val sourceUrl: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("photo_data")
    val photoData: String? = null,
    val categories: List<String> = emptyList()
)

