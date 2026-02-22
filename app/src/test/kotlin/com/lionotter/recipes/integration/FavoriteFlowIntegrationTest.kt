package com.lionotter.recipes.integration

import app.cash.turbine.turbineScope
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Integration tests for the favorite toggle flow using REAL Firestore SDK.
 *
 * These tests verify that RecipeRepository.setFavorite() — which uses
 * .update("isFavorite", value) — triggers real Firestore snapshot listeners
 * and that the RecipeDto @PropertyName annotation ensures the field name
 * matches between .set() and .update().
 */
class FavoriteFlowIntegrationTest : FirestoreIntegrationTest() {

    private val now = Instant.fromEpochMilliseconds(1700000000000)

    private fun createTestRecipe(
        id: String = "test-recipe-1",
        name: String = "Test Recipe",
        isFavorite: Boolean = false,
        updatedAt: Instant = now
    ) = Recipe(
        id = id,
        name = name,
        instructionSections = listOf(
            InstructionSection(
                name = null,
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Test step.",
                        ingredients = listOf(
                            Ingredient(
                                name = "test ingredient",
                                amount = Amount(value = 1.0, unit = "cup")
                            )
                        )
                    )
                )
            )
        ),
        tags = listOf("test"),
        equipment = listOf("bowl"),
        createdAt = now,
        updatedAt = updatedAt,
        isFavorite = isFavorite
    )

    // -----------------------------------------------------------------------
    // setFavorite and getRecipeById flow interaction
    // -----------------------------------------------------------------------

    @Test
    fun `setFavorite emits updated recipe from getRecipeById listener`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getRecipeById(recipe.id).testIn(backgroundScope)
            pumpLooper()

            // Initial emission
            val initial = turbine.awaitItem()
            assertNotNull(initial)
            assertFalse(initial!!.isFavorite)

            // Toggle favorite
            recipeRepository.setFavorite(recipe.id, true)
            pumpLooper()

            // Should get updated emission
            val updated = turbine.awaitItem()
            assertNotNull(updated)
            assertTrue(updated!!.isFavorite)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFavorite emits updated list from getAllRecipes listener`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            pumpLooper()

            // Initial emission
            val initial = turbine.awaitItem()
            assertTrue(initial.isNotEmpty())
            assertFalse(initial[0].isFavorite)

            // Toggle favorite
            recipeRepository.setFavorite(recipe.id, true)
            pumpLooper()

            // Should get updated emission
            val updated = turbine.awaitItem()
            assertTrue(updated.isNotEmpty())
            assertTrue(updated[0].isFavorite)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // ViewModel-style toggle: read current state from flow, flip, write back
    // -----------------------------------------------------------------------

    /**
     * Mimics exactly what RecipeListViewModel.toggleFavorite() does:
     * 1. Read current isFavorite from the getAllRecipes() flow
     * 2. Flip it
     * 3. Call setFavorite() with the flipped value
     * 4. Verify the flow emits the updated value
     *
     * This is the closest we can get to reproducing the real app's toggle
     * behavior at the repository level.
     */
    @Test
    fun `toggle favorite by reading current state from flow and flipping it`() = runTest {
        val recipe = createTestRecipe(isFavorite = false)
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            pumpLooper()

            // Step 1: Read current state from the flow (like ViewModel reads recipes.value)
            val initial = turbine.awaitItem()
            assertTrue("Should have one recipe", initial.isNotEmpty())
            val currentFavorite = initial[0].isFavorite
            assertFalse("Initial state should be not-favorite", currentFavorite)

            // Step 2: Flip and write back (exactly what toggleFavorite does)
            val newFavorite = !currentFavorite
            recipeRepository.setFavorite(recipe.id, newFavorite)
            pumpLooper()

            // Step 3: The flow should emit the updated value
            val updated = turbine.awaitItem()
            assertTrue("Should have one recipe after update", updated.isNotEmpty())
            assertTrue(
                "After toggle: expected isFavorite=$newFavorite but got ${updated[0].isFavorite}",
                updated[0].isFavorite == newFavorite
            )

            // Step 4: Toggle back (second click)
            val currentFavorite2 = updated[0].isFavorite
            assertTrue("Should now be favorite", currentFavorite2)
            val newFavorite2 = !currentFavorite2
            recipeRepository.setFavorite(recipe.id, newFavorite2)
            pumpLooper()

            val reverted = turbine.awaitItem()
            assertFalse(
                "After second toggle: expected isFavorite=$newFavorite2 but got ${reverted[0].isFavorite}",
                reverted[0].isFavorite
            )

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Rapid favorite toggles
    // -----------------------------------------------------------------------

    @Test
    fun `rapid favorite toggles all emit correctly`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getRecipeById(recipe.id).testIn(backgroundScope)
            pumpLooper()

            // Initial emission
            val initial = turbine.awaitItem()
            assertFalse(initial!!.isFavorite)

            // Toggle 5 times
            repeat(5) { i ->
                val newState = i % 2 == 0 // true, false, true, false, true
                recipeRepository.setFavorite(recipe.id, newState)
                pumpLooper()
                val result = turbine.awaitItem()
                assertTrue(
                    "Toggle $i: expected isFavorite=$newState but got ${result!!.isFavorite}",
                    result.isFavorite == newState
                )
            }

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Concurrent document and collection listeners
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent document and collection listeners both fire on setFavorite`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        turbineScope {
            val docTurbine = recipeRepository.getRecipeById(recipe.id).testIn(backgroundScope)
            val listTurbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            pumpLooper()

            // Both get initial emissions
            val initialDoc = docTurbine.awaitItem()
            val initialList = listTurbine.awaitItem()
            assertFalse(initialDoc!!.isFavorite)
            assertFalse(initialList[0].isFavorite)

            // Toggle favorite
            recipeRepository.setFavorite(recipe.id, true)
            pumpLooper()

            // Both should emit updates
            val updatedDoc = docTurbine.awaitItem()
            val updatedList = listTurbine.awaitItem()
            assertTrue(updatedDoc!!.isFavorite)
            assertTrue(updatedList[0].isFavorite)

            docTurbine.cancelAndIgnoreRemainingEvents()
            listTurbine.cancelAndIgnoreRemainingEvents()
        }
    }
}
