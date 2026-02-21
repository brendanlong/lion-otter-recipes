package com.lionotter.recipes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.Recipe
import kotlin.time.Instant

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val sourceUrl: String?,
    val story: String?,
    val servings: Int?,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?,
    val ingredientSectionsJson: String,
    val instructionSectionsJson: String,
    val equipmentJson: String = "[]",
    val tagsJson: String,
    val imageUrl: String?,
    val sourceImageUrl: String? = null,
    val originalHtml: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isFavorite: Boolean = false,
    val userNotes: String? = null
) {
    fun toRecipe(
        instructionSections: List<InstructionSection>,
        equipment: List<String>,
        tags: List<String>
    ): Recipe {
        return Recipe(
            id = id,
            name = name,
            sourceUrl = sourceUrl,
            story = story,
            servings = servings,
            prepTime = prepTime,
            cookTime = cookTime,
            totalTime = totalTime,
            instructionSections = instructionSections,
            equipment = equipment,
            tags = tags,
            imageUrl = imageUrl,
            sourceImageUrl = sourceImageUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isFavorite = isFavorite,
            userNotes = userNotes
        )
    }

    companion object {
        fun fromRecipe(
            recipe: Recipe,
            instructionSectionsJson: String,
            equipmentJson: String,
            tagsJson: String,
            originalHtml: String? = null
        ): RecipeEntity {
            return RecipeEntity(
                id = recipe.id,
                name = recipe.name,
                sourceUrl = recipe.sourceUrl,
                story = recipe.story,
                servings = recipe.servings,
                prepTime = recipe.prepTime,
                cookTime = recipe.cookTime,
                totalTime = recipe.totalTime,
                ingredientSectionsJson = "[]",
                instructionSectionsJson = instructionSectionsJson,
                equipmentJson = equipmentJson,
                tagsJson = tagsJson,
                imageUrl = recipe.imageUrl,
                sourceImageUrl = recipe.sourceImageUrl,
                originalHtml = originalHtml,
                createdAt = recipe.createdAt,
                updatedAt = recipe.updatedAt,
                isFavorite = recipe.isFavorite,
                userNotes = recipe.userNotes
            )
        }
    }
}
