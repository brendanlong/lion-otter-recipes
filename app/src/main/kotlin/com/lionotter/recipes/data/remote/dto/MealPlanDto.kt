package com.lionotter.recipes.data.remote.dto

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.util.enumOrNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Firestore field names of [MealPlanDto] used in queries and partial updates. Must match the
 * DTO's serialized property names (checked by DtoFieldDriftTest).
 */
object MealPlanFields {
    const val RECIPE_ID = "recipeId"
    const val RECIPE_IMAGE_URL = "recipeImageUrl"
}

data class MealPlanDto(
    @DocumentId val id: String = "",
    val recipeId: String = "",
    val recipeName: String = "",
    val recipeImageUrl: String? = null,
    val date: String = "",
    val mealType: String = "",
    val servings: Double = 1.0,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun toDomain(): MealPlanEntry = MealPlanEntry(
        id = id,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeImageUrl = recipeImageUrl,
        date = LocalDate.parse(date),
        mealType = enumOrNull<MealType>(mealType) ?: MealType.DINNER.also {
            Log.w("MealPlanDto", "Unknown meal type '$mealType' in meal plan $id; showing it as dinner")
        },
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
