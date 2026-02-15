package com.lionotter.recipes.data.remote.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinInstant

@IgnoreExtraProperties
data class MealPlanDto(
    @DocumentId val id: String = "",
    val recipeId: String = "",
    val recipeName: String = "",
    val recipeImageUrl: String? = null,
    val date: String = "",
    val mealType: String = MealType.DINNER.name,
    val servings: Double = 1.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomain(): MealPlanEntry {
        return MealPlanEntry(
            id = id,
            recipeId = recipeId,
            recipeName = recipeName,
            recipeImageUrl = recipeImageUrl,
            date = LocalDate.parse(date),
            mealType = try {
                MealType.valueOf(mealType)
            } catch (_: IllegalArgumentException) {
                MealType.DINNER
            },
            servings = servings,
            createdAt = createdAt?.toInstant()?.toKotlinInstant() ?: Instant.fromEpochMilliseconds(0),
            updatedAt = updatedAt?.toInstant()?.toKotlinInstant() ?: Instant.fromEpochMilliseconds(0)
        )
    }
}

fun MealPlanEntry.toDto(): MealPlanDto {
    return MealPlanDto(
        id = id,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeImageUrl = recipeImageUrl,
        date = date.toString(),
        mealType = mealType.name,
        servings = servings,
        createdAt = Timestamp(createdAt.epochSeconds, createdAt.nanosecondsOfSecond),
        updatedAt = Timestamp(updatedAt.epochSeconds, updatedAt.nanosecondsOfSecond)
    )
}
