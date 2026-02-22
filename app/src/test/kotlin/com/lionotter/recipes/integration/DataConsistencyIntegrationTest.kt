package com.lionotter.recipes.integration

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Integration tests verifying data consistency and round-trip fidelity
 * through the real RecipeRepository backed by the real Firestore SDK.
 *
 * These tests ensure that complex data structures (multi-section instructions,
 * equipment, nested ingredients) survive being stored and retrieved correctly.
 */
class DataConsistencyIntegrationTest : FirestoreIntegrationTest() {

    private val now = Instant.fromEpochMilliseconds(1700000000000)

    // -----------------------------------------------------------------------
    // Multi-section instruction round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `recipe with multi-section instructions survives database round-trip`() {
        val recipe = Recipe(
            id = "multi-section-recipe",
            name = "Complex Meal",
            instructionSections = listOf(
                InstructionSection(
                    name = "Dough",
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix flour and water.",
                            ingredients = listOf(
                                Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup")),
                                Ingredient(name = "water", amount = Amount(value = 1.0, unit = "cup"))
                            )
                        ),
                        InstructionStep(
                            stepNumber = 2,
                            instruction = "Knead for 10 minutes."
                        )
                    )
                ),
                InstructionSection(
                    name = "Sauce",
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Simmer tomatoes with garlic.",
                            ingredients = listOf(
                                Ingredient(name = "tomatoes", amount = Amount(value = 400.0, unit = "g")),
                                Ingredient(name = "garlic", amount = Amount(value = 3.0, unit = null))
                            )
                        )
                    )
                ),
                InstructionSection(
                    name = "Assembly",
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Roll out dough and add sauce."
                        )
                    )
                )
            ),
            tags = listOf("italian", "dinner"),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("multi-section-recipe") }

        assertNotNull(retrieved)
        assertEquals(3, retrieved!!.instructionSections.size)

        // Verify section names
        assertEquals("Dough", retrieved.instructionSections[0].name)
        assertEquals("Sauce", retrieved.instructionSections[1].name)
        assertEquals("Assembly", retrieved.instructionSections[2].name)

        // Verify step counts per section
        assertEquals(2, retrieved.instructionSections[0].steps.size)
        assertEquals(1, retrieved.instructionSections[1].steps.size)
        assertEquals(1, retrieved.instructionSections[2].steps.size)

        // Verify ingredients in steps
        val doughStep1 = retrieved.instructionSections[0].steps[0]
        assertEquals(2, doughStep1.ingredients.size)
        assertEquals("flour", doughStep1.ingredients[0].name)
        assertEquals(2.0, doughStep1.ingredients[0].amount!!.value!!, 0.001)
        assertEquals("cup", doughStep1.ingredients[0].amount!!.unit)

        val sauceStep1 = retrieved.instructionSections[1].steps[0]
        assertEquals("tomatoes", sauceStep1.ingredients[0].name)
        assertEquals(400.0, sauceStep1.ingredients[0].amount!!.value!!, 0.001)
        assertEquals("g", sauceStep1.ingredients[0].amount!!.unit)
    }

    // -----------------------------------------------------------------------
    // Equipment round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `recipe with equipment list survives database round-trip`() {
        val recipe = Recipe(
            id = "equipment-recipe",
            name = "Equipment Test",
            instructionSections = emptyList(),
            equipment = listOf("stand mixer", "baking sheet", "parchment paper", "wire rack"),
            tags = emptyList(),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("equipment-recipe") }

        assertNotNull(retrieved)
        assertEquals(4, retrieved!!.equipment.size)
        assertEquals(listOf("stand mixer", "baking sheet", "parchment paper", "wire rack"), retrieved.equipment)
    }

    // -----------------------------------------------------------------------
    // Tag round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `recipe with many tags survives database round-trip`() {
        val tags = listOf("breakfast", "quick", "healthy", "vegetarian", "under-30-min", "budget-friendly")
        val recipe = Recipe(
            id = "tag-recipe",
            name = "Tagged Recipe",
            instructionSections = emptyList(),
            tags = tags,
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("tag-recipe") }

        assertNotNull(retrieved)
        assertEquals(tags, retrieved!!.tags)
    }

    // -----------------------------------------------------------------------
    // Ingredient fields round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `complex ingredient fields survive database round-trip`() {
        val recipe = Recipe(
            id = "ingredient-fields-recipe",
            name = "Complex Ingredients",
            instructionSections = listOf(
                InstructionSection(
                    name = null,
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Combine all ingredients.",
                            ingredients = listOf(
                                Ingredient(
                                    name = "butter",
                                    amount = Amount(value = 0.5, unit = "cup"),
                                    notes = "softened, room temperature",
                                    density = 0.911,
                                    optional = false
                                ),
                                Ingredient(
                                    name = "vanilla extract",
                                    amount = Amount(value = 1.0, unit = "tsp"),
                                    optional = true
                                ),
                                Ingredient(
                                    name = "sugar",
                                    amount = Amount(value = 1.0, unit = "cup"),
                                    alternates = listOf(
                                        Ingredient(
                                            name = "honey",
                                            amount = Amount(value = 0.75, unit = "cup")
                                        )
                                    )
                                ),
                                Ingredient(
                                    name = "eggs",
                                    amount = Amount(value = 3.0, unit = null)
                                )
                            )
                        )
                    )
                )
            ),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("ingredient-fields-recipe") }

        assertNotNull(retrieved)
        val ingredients = retrieved!!.instructionSections[0].steps[0].ingredients

        // Butter — check notes and density
        val butter = ingredients.find { it.name == "butter" }!!
        assertEquals("softened, room temperature", butter.notes)
        assertEquals(0.911, butter.density!!, 0.001)
        assertEquals(false, butter.optional)

        // Vanilla — check optional
        val vanilla = ingredients.find { it.name == "vanilla extract" }!!
        assertTrue(vanilla.optional)

        // Sugar — check alternates
        val sugar = ingredients.find { it.name == "sugar" }!!
        assertEquals(1, sugar.alternates.size)
        assertEquals("honey", sugar.alternates[0].name)
        assertEquals(0.75, sugar.alternates[0].amount!!.value!!, 0.001)

        // Eggs — check count-based amount (no unit)
        val eggs = ingredients.find { it.name == "eggs" }!!
        val eggsAmount = eggs.amount!!
        assertEquals(3.0, eggsAmount.value!!, 0.001)
        assertEquals(null, eggsAmount.unit)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `recipe with empty instruction sections survives round-trip`() {
        val recipe = Recipe(
            id = "empty-instructions",
            name = "No Instructions",
            instructionSections = emptyList(),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("empty-instructions") }

        assertNotNull(retrieved)
        assertTrue(retrieved!!.instructionSections.isEmpty())
    }

    @Test
    fun `recipe with null optional fields survives round-trip`() {
        val recipe = Recipe(
            id = "minimal-recipe",
            name = "Minimal",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            instructionSections = emptyList(),
            equipment = emptyList(),
            tags = emptyList(),
            imageUrl = null,
            sourceImageUrl = null,
            createdAt = now,
            updatedAt = now,
            isFavorite = false
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("minimal-recipe") }

        assertNotNull(retrieved)
        assertEquals("Minimal", retrieved!!.name)
        assertEquals(null, retrieved.sourceUrl)
        assertEquals(null, retrieved.story)
        assertEquals(null, retrieved.servings)
        assertEquals(null, retrieved.prepTime)
        assertEquals(null, retrieved.cookTime)
        assertEquals(null, retrieved.totalTime)
        assertEquals(null, retrieved.imageUrl)
        assertEquals(null, retrieved.sourceImageUrl)
    }

    @Test
    fun `recipe with unicode content survives round-trip`() {
        val recipe = Recipe(
            id = "unicode-recipe",
            name = "Cr\u00e8me Br\u00fbl\u00e9e \ud83c\udf6e",
            story = "A classic French dessert with a caramelized sugar top. Tr\u00e8s magnifique! \u65e5\u672c\u8a9e\u30c6\u30b9\u30c8",
            instructionSections = listOf(
                InstructionSection(
                    name = "Cr\u00e8me",
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Heat cream to 180\u00b0F (82\u00b0C).",
                            ingredients = listOf(
                                Ingredient(
                                    name = "cr\u00e8me fra\u00eeche",
                                    amount = Amount(value = 2.0, unit = "cup"),
                                    notes = "or heavy cream (35% mati\u00e8res grasses)"
                                )
                            )
                        )
                    )
                )
            ),
            tags = listOf("fran\u00e7ais", "dessert", "\u65e5\u672c\u8a9e"),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("unicode-recipe") }

        assertNotNull(retrieved)
        assertEquals("Cr\u00e8me Br\u00fbl\u00e9e \ud83c\udf6e", retrieved!!.name)
        val story = retrieved.story!!
        assertTrue(story.contains("Tr\u00e8s magnifique"))
        assertTrue(story.contains("\u65e5\u672c\u8a9e\u30c6\u30b9\u30c8"))
        assertEquals("cr\u00e8me fra\u00eeche", retrieved.instructionSections[0].steps[0].ingredients[0].name)
        assertTrue(retrieved.tags.contains("fran\u00e7ais"))
        assertTrue(retrieved.tags.contains("\u65e5\u672c\u8a9e"))
    }

    @Test
    fun `save recipe twice overwrites cleanly`() {
        val original = Recipe(
            id = "overwrite-test",
            name = "Original Name",
            instructionSections = emptyList(),
            tags = listOf("original"),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(original)
        pumpLooper()
        assertEquals("Original Name", runSuspending { recipeRepository.getRecipeByIdOnce("overwrite-test") }!!.name)

        val updated = original.copy(
            name = "Updated Name",
            tags = listOf("updated"),
            updatedAt = Instant.fromEpochMilliseconds(1700000001000)
        )
        recipeRepository.saveRecipe(updated)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("overwrite-test") }
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved!!.name)
        assertEquals(listOf("updated"), retrieved.tags)
    }

    @Test
    fun `instruction step yields field survives round-trip`() {
        val recipe = Recipe(
            id = "yields-recipe",
            name = "Yields Test",
            instructionSections = listOf(
                InstructionSection(
                    name = null,
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Make the dough (yields 2 portions).",
                            ingredients = listOf(
                                Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup"))
                            ),
                            yields = 2
                        ),
                        InstructionStep(
                            stepNumber = 2,
                            instruction = "Assemble one portion.",
                            optional = true
                        )
                    )
                )
            ),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce("yields-recipe") }

        assertNotNull(retrieved)
        val steps = retrieved!!.instructionSections[0].steps
        assertEquals(2, steps[0].yields)
        assertTrue(steps[1].optional)
    }
}
