package com.lionotter.recipes.data.remote.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class MealPlanDto(
    @DocumentId val id: String = "",
    val recipeId: String = "",
    val recipeName: String = "",
    val recipeImageUrl: String? = null,
    val date: String = "",
    val mealType: String = "",
    val servings: Double = 1.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomain(): MealPlanEntry = MealPlanEntry(
        id = id,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeImageUrl = recipeImageUrl,
        date = LocalDate.parse(date),
        mealType = try { MealType.valueOf(mealType) } catch (_: Exception) { MealType.DINNER },
        servings = servings,
        createdAt = createdAt?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds) }
            ?: Instant.fromEpochMilliseconds(0),
        updatedAt = updatedAt?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds) }
            ?: Instant.fromEpochMilliseconds(0)
    )
}

fun MealPlanEntry.toDto(): MealPlanDto = MealPlanDto(
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
