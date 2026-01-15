package com.lionotter.recipes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.datetime.Instant

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
    val tagsJson: String,
    val imageUrl: String?,
    val originalHtml: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toRecipe(
        ingredientSections: List<IngredientSection>,
        instructionSections: List<InstructionSection>,
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
            ingredientSections = ingredientSections,
            instructionSections = instructionSections,
            tags = tags,
            imageUrl = imageUrl,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt)
        )
    }

    companion object {
        fun fromRecipe(
            recipe: Recipe,
            ingredientSectionsJson: String,
            instructionSectionsJson: String,
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
                ingredientSectionsJson = ingredientSectionsJson,
                instructionSectionsJson = instructionSectionsJson,
                tagsJson = tagsJson,
                imageUrl = recipe.imageUrl,
                originalHtml = originalHtml,
                createdAt = recipe.createdAt.toEpochMilliseconds(),
                updatedAt = recipe.updatedAt.toEpochMilliseconds()
            )
        }
    }
}
