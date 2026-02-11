package com.lionotter.recipes.data.remote

import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts Recipe and MealPlanEntry to/from native Firestore maps.
 *
 * Uses kotlinx.serialization's JsonElement as an intermediary to convert
 * @Serializable domain models to Map<String, Any?> for Firestore storage.
 * This avoids Firestore's reflection-based serialization which doesn't handle
 * our custom types (Instant, LocalDate).
 *
 * Firestore stores native maps, arrays, strings, numbers, and booleans —
 * not JSON strings.
 */
@Singleton
class FirestoreMapConverter @Inject constructor(
    private val json: Json
) {
    fun recipeToMap(recipe: Recipe): Map<String, Any?> {
        val element = json.encodeToJsonElement(Recipe.serializer(), recipe)
        val result = jsonElementToAny(element)
        @Suppress("UNCHECKED_CAST")
        return (result as? Map<String, Any?>) ?: emptyMap()
    }

    fun mapToRecipe(map: Map<String, Any?>): Recipe {
        val element = anyToJsonElement(map)
        return json.decodeFromJsonElement(Recipe.serializer(), element)
    }

    fun mealPlanToMap(entry: MealPlanEntry): Map<String, Any?> {
        val element = json.encodeToJsonElement(MealPlanEntry.serializer(), entry)
        val result = jsonElementToAny(element)
        @Suppress("UNCHECKED_CAST")
        return (result as? Map<String, Any?>) ?: emptyMap()
    }

    fun mapToMealPlan(map: Map<String, Any?>): MealPlanEntry {
        val element = anyToJsonElement(map)
        return json.decodeFromJsonElement(MealPlanEntry.serializer(), element)
    }

    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
        is JsonArray -> element.map { jsonElementToAny(it) }
        is JsonObject -> element.entries.associate { (k, v) -> k to jsonElementToAny(v) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        is Map<*, *> -> JsonObject(
            (value as Map<String, Any?>).entries.associate { (k, v) ->
                k to anyToJsonElement(v)
            }
        )
        else -> JsonPrimitive(value.toString())
    }
}
