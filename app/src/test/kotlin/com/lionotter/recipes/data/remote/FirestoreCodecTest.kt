package com.lionotter.recipes.data.remote

import com.google.firebase.Timestamp
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreCodecTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val testRecipe = Recipe(
        id = "abc123",
        name = "Pasta Carbonara",
        servings = 4,
        instructionSections = listOf(
            InstructionSection(
                name = "Main",
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Cook pasta",
                        ingredients = listOf(
                            Ingredient(
                                name = "spaghetti",
                                amount = Amount(value = 400.0, unit = "g")
                            )
                        )
                    )
                )
            )
        ),
        tags = listOf("italian", "pasta"),
        createdAt = Instant.parse("2024-02-04T12:00:00Z"),
        updatedAt = Instant.parse("2024-02-04T12:00:00Z"),
        isFavorite = false
    )

    private val testMealPlanEntry = MealPlanEntry(
        id = "def456",
        recipeId = "abc123",
        recipeName = "Pasta Carbonara",
        recipeImageUrl = null,
        date = LocalDate(2024, 2, 4),
        mealType = MealType.DINNER,
        servings = 1.5,
        createdAt = Instant.parse("2024-02-04T12:00:00Z"),
        updatedAt = Instant.parse("2024-02-04T12:00:00Z")
    )

    @Test
    fun `recipe round-trip preserves all fields`() {
        val map = json.encodeToFirestoreMap(testRecipe, RECIPE_TIMESTAMP_FIELDS)

        // Simulate Firestore round-trip: Timestamps are preserved as-is,
        // other values go through Firestore's native types
        val decoded = json.decodeFromFirestoreMap<Recipe>(map)

        assertEquals(testRecipe, decoded)
    }

    @Test
    fun `recipe map has correct top-level keys`() {
        val map = json.encodeToFirestoreMap(testRecipe, RECIPE_TIMESTAMP_FIELDS)

        assertTrue("id" in map)
        assertTrue("name" in map)
        assertTrue("servings" in map)
        assertTrue("instructionSections" in map)
        assertTrue("tags" in map)
        assertTrue("createdAt" in map)
        assertTrue("updatedAt" in map)
        assertTrue("isFavorite" in map)

        assertEquals("abc123", map["id"])
        assertEquals("Pasta Carbonara", map["name"])
        assertEquals(false, map["isFavorite"])
    }

    @Test
    fun `recipe map has nested maps for instruction sections`() {
        val map = json.encodeToFirestoreMap(testRecipe, RECIPE_TIMESTAMP_FIELDS)

        @Suppress("UNCHECKED_CAST")
        val sections = map["instructionSections"] as List<Map<String, Any?>>
        assertEquals(1, sections.size)
        assertEquals("Main", sections[0]["name"])

        @Suppress("UNCHECKED_CAST")
        val steps = sections[0]["steps"] as List<Map<String, Any?>>
        assertEquals(1, steps.size)
        assertEquals("Cook pasta", steps[0]["instruction"])
    }

    @Test
    fun `recipe map has arrays for tags`() {
        val map = json.encodeToFirestoreMap(testRecipe, RECIPE_TIMESTAMP_FIELDS)

        @Suppress("UNCHECKED_CAST")
        val tags = map["tags"] as List<String>
        assertEquals(listOf("italian", "pasta"), tags)
    }

    @Test
    fun `recipe timestamps are Firestore Timestamp objects`() {
        val map = json.encodeToFirestoreMap(testRecipe, RECIPE_TIMESTAMP_FIELDS)

        assertTrue("createdAt should be Timestamp", map["createdAt"] is Timestamp)
        assertTrue("updatedAt should be Timestamp", map["updatedAt"] is Timestamp)
    }

    @Test
    fun `meal plan entry round-trip preserves all fields`() {
        val map = json.encodeToFirestoreMap(testMealPlanEntry, MEAL_PLAN_TIMESTAMP_FIELDS)
        val decoded = json.decodeFromFirestoreMap<MealPlanEntry>(map)

        assertEquals(testMealPlanEntry, decoded)
    }

    @Test
    fun `meal plan entry timestamps are Firestore Timestamp objects`() {
        val map = json.encodeToFirestoreMap(testMealPlanEntry, MEAL_PLAN_TIMESTAMP_FIELDS)

        assertTrue("createdAt should be Timestamp", map["createdAt"] is Timestamp)
        assertTrue("updatedAt should be Timestamp", map["updatedAt"] is Timestamp)
    }

    @Test
    fun `meal plan entry date stays as string`() {
        val map = json.encodeToFirestoreMap(testMealPlanEntry, MEAL_PLAN_TIMESTAMP_FIELDS)

        // LocalDate is serialized as a string, not converted to Timestamp
        assertEquals("2024-02-04", map["date"])
    }

    @Test
    fun `ignoreUnknownKeys handles extra fields on decode`() {
        val map = json.encodeToFirestoreMap(testRecipe, RECIPE_TIMESTAMP_FIELDS).toMutableMap()
        map["originalHtml"] = "<html>test</html>"
        map["unknownField"] = "should be ignored"

        // Should not throw
        val decoded = json.decodeFromFirestoreMap<Recipe>(map)
        assertEquals(testRecipe, decoded)
    }

    @Test
    fun `int survives Long round-trip`() {
        val map = json.encodeToFirestoreMap(testRecipe, RECIPE_TIMESTAMP_FIELDS)

        // Firestore stores numbers as Long, simulate this
        val firestoreMap = map.toMutableMap()
        // servings is Int(4), which encodes as Long(4) in JsonPrimitive
        // After Firestore round-trip, it comes back as Long(4)
        val servingsValue = firestoreMap["servings"]
        assertNotNull(servingsValue)

        // Decode should still work because our codec handles Long→Int conversion
        val decoded = json.decodeFromFirestoreMap<Recipe>(firestoreMap)
        assertEquals(4, decoded.servings)
    }

    @Test
    fun `double values in amounts are preserved`() {
        val map = json.encodeToFirestoreMap(testMealPlanEntry, MEAL_PLAN_TIMESTAMP_FIELDS)
        assertEquals(1.5, map["servings"])

        val decoded = json.decodeFromFirestoreMap<MealPlanEntry>(map)
        assertEquals(1.5, decoded.servings, 0.001)
    }

    @Test
    fun `recipe with null optional fields round-trips correctly`() {
        val minimalRecipe = Recipe(
            id = "min1",
            name = "Simple",
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        val map = json.encodeToFirestoreMap(minimalRecipe, RECIPE_TIMESTAMP_FIELDS)
        val decoded = json.decodeFromFirestoreMap<Recipe>(map)

        assertEquals(minimalRecipe, decoded)
    }
}
