package com.lionotter.recipes.data.remote.dto

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.Recipe
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Json instance matching the app-wide configuration from AppModule.
 * Used to serialize/deserialize instructionSections as a JSON string
 * to avoid deeply nested Firestore maps that cause protobuf equals()
 * performance issues in the Firestore SDK.
 */
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}

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
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val tags: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val instructionSectionsJson: String = "[]"
) {
    fun toDomain(): Recipe {
        val sections = try {
            json.decodeFromString<List<InstructionSection>>(instructionSectionsJson)
        } catch (e: Exception) {
            Log.e("RecipeDto", "Failed to parse instructionSectionsJson", e)
            emptyList()
        }

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
            createdAt = createdAt?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds) }
                ?: Instant.fromEpochMilliseconds(0),
            updatedAt = updatedAt?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds) }
                ?: Instant.fromEpochMilliseconds(0),
            tags = tags,
            equipment = equipment,
            instructionSections = sections
        )
    }
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
    instructionSectionsJson = json.encodeToString(instructionSections)
)
