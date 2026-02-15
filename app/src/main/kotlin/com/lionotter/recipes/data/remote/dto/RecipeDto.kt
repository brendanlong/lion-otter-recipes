package com.lionotter.recipes.data.remote.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant

@IgnoreExtraProperties
data class RecipeDto(
    @DocumentId val id: String = "",
    val name: String = "",
    val sourceUrl: String? = null,
    val story: String? = null,
    val imageUrl: String? = null,
    val sourceImageUrl: String? = null,
    val servings: Long? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    @get:PropertyName("isFavorite") @set:PropertyName("isFavorite") var isFavorite: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    val tags: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val instructionSections: List<InstructionSectionDto> = emptyList()
) {
    fun toDomain(): Recipe {
        return Recipe(
            id = id,
            name = name,
            sourceUrl = sourceUrl,
            story = story,
            imageUrl = imageUrl,
            sourceImageUrl = sourceImageUrl,
            servings = servings?.toInt(),
            prepTime = prepTime,
            cookTime = cookTime,
            totalTime = totalTime,
            isFavorite = isFavorite,
            createdAt = createdAt?.toInstant()?.toKotlinInstant() ?: Instant.fromEpochMilliseconds(0),
            updatedAt = updatedAt?.toInstant()?.toKotlinInstant() ?: Instant.fromEpochMilliseconds(0),
            tags = tags,
            equipment = equipment,
            instructionSections = instructionSections.map { it.toDomain() }
        )
    }
}

data class InstructionSectionDto(
    val name: String? = null,
    val steps: List<InstructionStepDto> = emptyList()
) {
    fun toDomain(): InstructionSection {
        return InstructionSection(
            name = name,
            steps = steps.map { it.toDomain() }
        )
    }
}

data class InstructionStepDto(
    val stepNumber: Long = 0,
    val instruction: String = "",
    val ingredients: List<IngredientDto> = emptyList(),
    val yields: Long = 1,
    val optional: Boolean = false
) {
    fun toDomain(): InstructionStep {
        return InstructionStep(
            stepNumber = stepNumber.toInt(),
            instruction = instruction,
            ingredients = ingredients.map { it.toDomain() },
            yields = yields.toInt(),
            optional = optional
        )
    }
}

data class IngredientDto(
    val name: String = "",
    val notes: String? = null,
    val optional: Boolean = false,
    val density: Double? = null,
    val amount: AmountDto? = null,
    val alternates: List<IngredientDto> = emptyList()
) {
    fun toDomain(): Ingredient {
        return Ingredient(
            name = name,
            notes = notes,
            optional = optional,
            density = density,
            amount = amount?.toDomain(),
            alternates = alternates.map { it.toDomain() }
        )
    }
}

data class AmountDto(
    val value: Double? = null,
    val unit: String? = null
) {
    fun toDomain(): Amount {
        return Amount(value = value, unit = unit)
    }
}

// Extension functions for domain -> DTO conversion

fun Recipe.toDto(): RecipeDto {
    return RecipeDto(
        id = id,
        name = name,
        sourceUrl = sourceUrl,
        story = story,
        imageUrl = imageUrl,
        sourceImageUrl = sourceImageUrl,
        servings = servings?.toLong(),
        prepTime = prepTime,
        cookTime = cookTime,
        totalTime = totalTime,
        isFavorite = isFavorite,
        createdAt = Timestamp(createdAt.epochSeconds, createdAt.nanosecondsOfSecond),
        updatedAt = Timestamp(updatedAt.epochSeconds, updatedAt.nanosecondsOfSecond),
        tags = tags,
        equipment = equipment,
        instructionSections = instructionSections.map { it.toDto() }
    )
}

fun InstructionSection.toDto(): InstructionSectionDto {
    return InstructionSectionDto(
        name = name,
        steps = steps.map { it.toDto() }
    )
}

fun InstructionStep.toDto(): InstructionStepDto {
    return InstructionStepDto(
        stepNumber = stepNumber.toLong(),
        instruction = instruction,
        ingredients = ingredients.map { it.toDto() },
        yields = yields.toLong(),
        optional = optional
    )
}

fun Ingredient.toDto(): IngredientDto {
    return IngredientDto(
        name = name,
        notes = notes,
        optional = optional,
        density = density,
        amount = amount?.toDto(),
        alternates = alternates.map { it.toDto() }
    )
}

fun Amount.toDto(): AmountDto {
    return AmountDto(value = value, unit = unit)
}
