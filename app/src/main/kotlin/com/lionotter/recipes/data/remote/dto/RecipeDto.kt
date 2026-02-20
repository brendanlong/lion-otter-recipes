package com.lionotter.recipes.data.remote.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import kotlin.time.Instant

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
    @get:PropertyName("isFavorite") @set:PropertyName("isFavorite")
    var isFavorite: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    val tags: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val instructionSections: List<InstructionSectionDto> = emptyList()
) {
    fun toDomain(): Recipe = Recipe(
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
        createdAt = createdAt?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds) }
            ?: Instant.fromEpochMilliseconds(0),
        updatedAt = updatedAt?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds) }
            ?: Instant.fromEpochMilliseconds(0),
        tags = tags,
        equipment = equipment,
        instructionSections = instructionSections.map { it.toDomain() }
    )
}

fun Recipe.toDto(): RecipeDto = RecipeDto(
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

data class InstructionSectionDto(
    val name: String? = null,
    val steps: List<InstructionStepDto> = emptyList()
) {
    fun toDomain(): InstructionSection = InstructionSection(
        name = name,
        steps = steps.map { it.toDomain() }
    )
}

fun InstructionSection.toDto(): InstructionSectionDto = InstructionSectionDto(
    name = name,
    steps = steps.map { it.toDto() }
)

data class InstructionStepDto(
    val stepNumber: Long = 0,
    val instruction: String = "",
    val yields: Long = 0,
    val optional: Boolean = false,
    val ingredients: List<IngredientDto> = emptyList()
) {
    fun toDomain(): InstructionStep = InstructionStep(
        stepNumber = stepNumber.toInt(),
        instruction = instruction,
        yields = yields.toInt(),
        optional = optional,
        ingredients = ingredients.map { it.toDomain() }
    )
}

fun InstructionStep.toDto(): InstructionStepDto = InstructionStepDto(
    stepNumber = stepNumber.toLong(),
    instruction = instruction,
    yields = yields.toLong(),
    optional = optional,
    ingredients = ingredients.map { it.toDto() }
)

data class IngredientDto(
    val name: String = "",
    val notes: String? = null,
    val optional: Boolean = false,
    val density: Double? = null,
    val amount: AmountDto = AmountDto(),
    val alternates: List<IngredientDto> = emptyList()
) {
    fun toDomain(): Ingredient = Ingredient(
        name = name,
        notes = notes,
        optional = optional,
        density = density,
        amount = amount.toDomain(),
        alternates = alternates.map { it.toDomain() }
    )
}

fun Ingredient.toDto(): IngredientDto = IngredientDto(
    name = name,
    notes = notes,
    optional = optional,
    density = density,
    amount = amount?.toDto() ?: AmountDto(),
    alternates = alternates.map { it.toDto() }
)

data class AmountDto(
    val value: Double? = null,
    val unit: String? = null
) {
    fun toDomain(): Amount = Amount(value = value, unit = unit)
}

fun Amount.toDto(): AmountDto = AmountDto(value = value, unit = unit)
