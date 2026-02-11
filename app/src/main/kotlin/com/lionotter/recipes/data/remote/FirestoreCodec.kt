package com.lionotter.recipes.data.remote

import com.google.firebase.Timestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import java.time.Instant as JavaInstant

/**
 * Converts a [JsonElement] to a Firestore-compatible native value.
 *
 * - [JsonObject] → [Map]<String, Any?>
 * - [JsonArray] → [List]<Any?>
 * - [JsonPrimitive] → Boolean, Long, Double, or String
 * - [JsonNull] → null
 */
fun JsonElement.toFirestoreValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> this.mapValues { (_, v) -> v.toFirestoreValue() }
    is JsonArray -> this.map { it.toFirestoreValue() }
    is JsonPrimitive -> when {
        this.booleanOrNull != null -> this.boolean
        this.longOrNull != null -> this.long
        this.doubleOrNull != null -> this.double
        else -> this.content // string (strips quotes automatically)
    }
}

/**
 * Converts a Firestore native value back to a [JsonElement].
 *
 * Handles: null, Boolean, Number, String, List, Map, and
 * [com.google.firebase.Timestamp] (converted to ISO-8601 string).
 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this.toDouble())
    is Double -> {
        // Firestore returns integers as Long, but sometimes as Double.
        // If the double is actually a whole number, encode as long to
        // match the original Int/Long encoding.
        if (this == kotlin.math.floor(this) && !this.isInfinite() && !this.isNaN()) {
            JsonPrimitive(this.toLong())
        } else {
            JsonPrimitive(this)
        }
    }
    is String -> JsonPrimitive(this)
    is Timestamp -> {
        val instant = JavaInstant.ofEpochSecond(this.seconds, this.nanoseconds.toLong())
        JsonPrimitive(instant.toString())
    }
    is List<*> -> JsonArray(this.map { it.toJsonElement() })
    is Map<*, *> -> JsonObject(
        this.entries.associate { (k, v) -> k.toString() to v.toJsonElement() }
    )
    else -> JsonPrimitive(this.toString())
}

/** Fields that hold ISO-8601 Instant timestamps in Recipe documents. */
val RECIPE_TIMESTAMP_FIELDS = setOf("createdAt", "updatedAt")

/** Fields that hold ISO-8601 Instant timestamps in MealPlanEntry documents. */
val MEAL_PLAN_TIMESTAMP_FIELDS = setOf("createdAt", "updatedAt")

/**
 * Encode a @Serializable object to a Firestore-compatible [Map], then convert
 * specified ISO-8601 string fields to [Timestamp].
 */
inline fun <reified T> Json.encodeToFirestoreMap(
    value: T,
    timestampFields: Set<String> = emptySet()
): Map<String, Any?> {
    val jsonElement = this.encodeToJsonElement(serializer<T>(), value)
    @Suppress("UNCHECKED_CAST")
    val map = jsonElement.toFirestoreValue() as Map<String, Any?>
    if (timestampFields.isEmpty()) return map

    return map.toMutableMap().apply {
        for (field in timestampFields) {
            val isoString = this[field] as? String ?: continue
            this[field] = isoStringToTimestamp(isoString)
        }
    }
}

/**
 * Decode a Firestore document [Map] back to a @Serializable object, first converting
 * any [Timestamp] fields back to ISO-8601 strings.
 */
inline fun <reified T> Json.decodeFromFirestoreMap(
    map: Map<String, Any?>
): T {
    // Timestamps are already handled by Any?.toJsonElement() which converts
    // Timestamp → ISO string, so no pre-processing needed.
    val jsonElement = map.toJsonElement()
    return this.decodeFromJsonElement(serializer<T>(), jsonElement)
}

/**
 * Parse an ISO-8601 instant string to a Firestore [Timestamp].
 */
fun isoStringToTimestamp(iso: String): Timestamp {
    val instant = JavaInstant.parse(iso)
    return Timestamp(instant.epochSecond, instant.nano)
}
