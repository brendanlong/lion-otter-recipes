package com.lionotter.recipes.data.remote.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.util.AppJson
import kotlin.time.Instant
import kotlinx.serialization.encodeToString

/**
 * Firestore field names of [RecipeDto] used in queries and partial updates. Must match the
 * DTO's serialized property names (checked by DtoFieldDriftTest).
 */
object RecipeFields {
    const val NAME = "name"
    const val SOURCE_URL = "sourceUrl"
    const val IMAGE_URL = "imageUrl"
    const val IS_FAVORITE = "isFavorite"
    const val USER_NOTES = "userNotes"
    const val TAGS = "tags"
    const val IMAGE_PROMPT = "imagePrompt"
}

/**
 * Thrown when a recipe document can't be decoded faithfully. Such a recipe is left out of
 * listings (and fails single reads) rather than loaded with missing content that a later
 * full save would write back over the stored data.
 */
class CorruptRecipeException(recipeId: String, cause: Throwable) :
    IllegalStateException("Recipe $recipeId has unreadable instructions", cause)

/**
 * Firestore shape of a [Recipe]. instructionSections are stored as a JSON string
 * to avoid deeply nested Firestore maps that cause protobuf equals() performance
 * issues in the Firestore SDK.
 */
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
    @get:PropertyName(RecipeFields.IS_FAVORITE) @set:PropertyName(RecipeFields.IS_FAVORITE)
    var isFavorite: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val tags: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val instructionSectionsJson: String = "[]",
    val userNotes: String? = null,
    val imagePrompt: String? = null
) {
    /** @throws CorruptRecipeException if [instructionSectionsJson] can't be parsed */
    fun toDomain(): Recipe {
        val sections = try {
            AppJson.decodeFromString<List<InstructionSection>>(instructionSectionsJson)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization's SerializationException is an IllegalArgumentException
            throw CorruptRecipeException(id, e)
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
            instructionSections = sections,
            userNotes = userNotes,
            imagePrompt = imagePrompt
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
    instructionSectionsJson = AppJson.encodeToString(instructionSections),
    userNotes = userNotes,
    imagePrompt = imagePrompt
)
