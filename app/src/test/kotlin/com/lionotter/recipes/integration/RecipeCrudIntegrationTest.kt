package com.lionotter.recipes.integration

import app.cash.turbine.turbineScope
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Integration tests for Recipe CRUD operations through the real
 * RecipeRepository backed by the real Firestore SDK (offline, in-memory cache).
 */
class RecipeCrudIntegrationTest : FirestoreIntegrationTest() {

    private val now = Instant.fromEpochMilliseconds(1700000000000)

    private fun createTestRecipe(
        id: String = "test-recipe-1",
        name: String = "Classic Chocolate Chip Cookies",
        isFavorite: Boolean = false
    ) = Recipe(
        id = id,
        name = name,
        sourceUrl = "https://example.com/cookies",
        story = "A family recipe passed down through generations.",
        servings = 24,
        prepTime = "15 min",
        cookTime = "12 min",
        totalTime = "27 min",
        instructionSections = listOf(
            InstructionSection(
                name = null,
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Preheat oven to 375\u00b0F (190\u00b0C).",
                        ingredients = emptyList()
                    ),
                    InstructionStep(
                        stepNumber = 2,
                        instruction = "Cream together butter and sugars until fluffy.",
                        ingredients = listOf(
                            Ingredient(
                                name = "butter",
                                amount = Amount(value = 1.0, unit = "cup")
                            ),
                            Ingredient(
                                name = "brown sugar",
                                amount = Amount(value = 0.75, unit = "cup")
                            )
                        )
                    ),
                    InstructionStep(
                        stepNumber = 3,
                        instruction = "Fold in chocolate chips.",
                        ingredients = listOf(
                            Ingredient(
                                name = "chocolate chips",
                                amount = Amount(value = 2.0, unit = "cup")
                            )
                        )
                    )
                )
            )
        ),
        equipment = listOf("mixing bowl", "baking sheet"),
        tags = listOf("dessert", "cookies", "baking"),
        createdAt = now,
        updatedAt = now,
        isFavorite = isFavorite
    )

    // -----------------------------------------------------------------------
    // Insert and retrieve
    // -----------------------------------------------------------------------

    @Test
    fun `save recipe via repository then retrieve it`() {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        val retrieved = runSuspending { recipeRepository.getRecipeByIdOnce(recipe.id) }
        assertNotNull(retrieved)
        assertEquals(recipe.name, retrieved!!.name)
        assertEquals(recipe.sourceUrl, retrieved.sourceUrl)
        assertEquals(recipe.story, retrieved.story)
        assertEquals(recipe.servings, retrieved.servings)
        assertEquals(recipe.prepTime, retrieved.prepTime)
        assertEquals(recipe.cookTime, retrieved.cookTime)
        assertEquals(recipe.totalTime, retrieved.totalTime)
        assertEquals(recipe.tags, retrieved.tags)
        assertEquals(recipe.equipment, retrieved.equipment)
        assertFalse(retrieved.isFavorite)
    }

    @Test
    fun `saved recipe appears in getAllRecipes flow`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            pumpLooper()
            val recipes = turbine.awaitItem()
            assertEquals(1, recipes.size)
            assertEquals(recipe.name, recipes[0].name)
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple recipes are all returned`() = runTest {
        val recipe1 = createTestRecipe(id = "recipe-1", name = "Recipe A")
        val recipe2 = createTestRecipe(id = "recipe-2", name = "Recipe B")

        recipeRepository.saveRecipe(recipe1)
        recipeRepository.saveRecipe(recipe2)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            pumpLooper()
            val recipes = turbine.awaitItem()
            assertEquals(2, recipes.size)
            val names = recipes.map { it.name }.toSet()
            assertTrue(names.contains("Recipe A"))
            assertTrue(names.contains("Recipe B"))
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Favorite toggle
    // -----------------------------------------------------------------------

    @Test
    fun `toggle favorite via repository persists in database`() {
        val recipe = createTestRecipe(isFavorite = false)
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        // Toggle to favorite
        recipeRepository.setFavorite(recipe.id, true)
        pumpLooper()

        val updated = runSuspending { recipeRepository.getRecipeByIdOnce(recipe.id) }
        assertNotNull(updated)
        assertTrue(updated!!.isFavorite)

        // Toggle back
        recipeRepository.setFavorite(recipe.id, false)
        pumpLooper()

        val reverted = runSuspending { recipeRepository.getRecipeByIdOnce(recipe.id) }
        assertNotNull(reverted)
        assertFalse(reverted!!.isFavorite)
    }

    @Test
    fun `favorite toggle is reflected in getAllRecipes flow`() = runTest {
        val recipe = createTestRecipe(isFavorite = false)
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            pumpLooper()

            // Initial emission — not favorited
            val initial = turbine.awaitItem()
            assertFalse(initial[0].isFavorite)

            // Toggle favorite
            recipeRepository.setFavorite(recipe.id, true)
            pumpLooper()

            val updated = turbine.awaitItem()
            assertTrue(updated[0].isFavorite)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    @Test
    fun `delete recipe removes it from database`() {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        // Confirm it exists
        assertNotNull(runSuspending { recipeRepository.getRecipeByIdOnce(recipe.id) })

        // Delete
        recipeRepository.deleteRecipe(recipe.id)
        pumpLooper()

        // Confirm it's gone
        assertNull(runSuspending { recipeRepository.getRecipeByIdOnce(recipe.id) })
    }

    @Test
    fun `delete recipe is reflected in getAllRecipes flow`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            pumpLooper()

            // Initial emission — recipe exists
            val initial = turbine.awaitItem()
            assertEquals(1, initial.size)

            // Delete
            recipeRepository.deleteRecipe(recipe.id)
            pumpLooper()

            val afterDelete = turbine.awaitItem()
            assertTrue(afterDelete.isEmpty())

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // IDs and names
    // -----------------------------------------------------------------------

    @Test
    fun `getAllRecipeIdsAndNames returns all recipes`() {
        recipeRepository.saveRecipe(createTestRecipe(id = "r1", name = "Recipe A"))
        recipeRepository.saveRecipe(createTestRecipe(id = "r2", name = "Recipe B"))
        pumpLooper()

        val idsAndNames = runSuspending { recipeRepository.getAllRecipeIdsAndNames() }
        assertEquals(2, idsAndNames.size)
        assertTrue(idsAndNames.any { it.id == "r1" && it.name == "Recipe A" })
        assertTrue(idsAndNames.any { it.id == "r2" && it.name == "Recipe B" })
    }
}
