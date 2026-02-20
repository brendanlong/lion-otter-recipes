package com.lionotter.recipes.integration

import app.cash.turbine.turbineScope
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Integration tests for Recipe CRUD operations through the full
 * DAO → Repository pipeline backed by a real in-memory Room database.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class RecipeCrudIntegrationTest : HiltIntegrationTest() {

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
                        instruction = "Preheat oven to 375°F (190°C).",
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
    fun `save recipe via repository then retrieve it`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)

        val retrieved = recipeRepository.getRecipeByIdOnce(recipe.id)
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

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            val recipes = turbine.awaitItem()
            assertEquals(1, recipes.size)
            assertEquals(recipe.name, recipes[0].name)
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple recipes are returned ordered by updatedAt desc`() = runTest {
        val recipe1 = createTestRecipe(
            id = "recipe-1",
            name = "Older Recipe"
        ).copy(updatedAt = Instant.fromEpochMilliseconds(1000))
        val recipe2 = createTestRecipe(
            id = "recipe-2",
            name = "Newer Recipe"
        ).copy(updatedAt = Instant.fromEpochMilliseconds(2000))

        recipeRepository.saveRecipe(recipe1)
        recipeRepository.saveRecipe(recipe2)

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            val recipes = turbine.awaitItem()
            assertEquals(2, recipes.size)
            assertEquals("Newer Recipe", recipes[0].name)
            assertEquals("Older Recipe", recipes[1].name)
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Favorite toggle
    // -----------------------------------------------------------------------

    @Test
    fun `toggle favorite via repository persists in database`() = runTest {
        val recipe = createTestRecipe(isFavorite = false)
        recipeRepository.saveRecipe(recipe)

        // Toggle to favorite
        recipeRepository.setFavorite(recipe.id, true)

        val updated = recipeRepository.getRecipeByIdOnce(recipe.id)
        assertNotNull(updated)
        assertTrue(updated!!.isFavorite)

        // Toggle back
        recipeRepository.setFavorite(recipe.id, false)
        val reverted = recipeRepository.getRecipeByIdOnce(recipe.id)
        assertNotNull(reverted)
        assertFalse(reverted!!.isFavorite)
    }

    @Test
    fun `favorite toggle is reflected in getAllRecipes flow`() = runTest {
        val recipe = createTestRecipe(isFavorite = false)
        recipeRepository.saveRecipe(recipe)

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)

            // Initial emission — not favorited
            val initial = turbine.awaitItem()
            assertFalse(initial[0].isFavorite)

            // Toggle favorite
            recipeRepository.setFavorite(recipe.id, true)

            val updated = turbine.awaitItem()
            assertTrue(updated[0].isFavorite)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    @Test
    fun `delete recipe removes it from database`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)

        // Confirm it exists
        assertNotNull(recipeRepository.getRecipeByIdOnce(recipe.id))

        // Delete
        recipeRepository.deleteRecipe(recipe.id)

        // Confirm it's gone
        assertNull(recipeRepository.getRecipeByIdOnce(recipe.id))
    }

    @Test
    fun `delete recipe is reflected in getAllRecipes flow`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)

            // Initial emission — recipe exists
            val initial = turbine.awaitItem()
            assertEquals(1, initial.size)

            // Delete
            recipeRepository.deleteRecipe(recipe.id)

            val afterDelete = turbine.awaitItem()
            assertTrue(afterDelete.isEmpty())

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    @Test
    fun `search recipes returns matching results`() = runTest {
        recipeRepository.saveRecipe(createTestRecipe(id = "r1", name = "Chocolate Cake"))
        recipeRepository.saveRecipe(createTestRecipe(id = "r2", name = "Banana Bread"))
        recipeRepository.saveRecipe(createTestRecipe(id = "r3", name = "Chocolate Mousse"))

        turbineScope {
            val turbine = recipeRepository.searchRecipes("Chocolate").testIn(backgroundScope)
            val results = turbine.awaitItem()
            assertEquals(2, results.size)
            assertTrue(results.all { it.name.contains("Chocolate") })
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search recipes with no match returns empty list`() = runTest {
        recipeRepository.saveRecipe(createTestRecipe(id = "r1", name = "Chocolate Cake"))

        turbineScope {
            val turbine = recipeRepository.searchRecipes("Pizza").testIn(backgroundScope)
            val results = turbine.awaitItem()
            assertTrue(results.isEmpty())
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Tag filtering
    // -----------------------------------------------------------------------

    @Test
    fun `get recipes by tag filters correctly`() = runTest {
        val dessert = createTestRecipe(id = "r1", name = "Cookies")
        val dinner = Recipe(
            id = "r2",
            name = "Pasta",
            instructionSections = emptyList(),
            tags = listOf("dinner", "pasta"),
            createdAt = now,
            updatedAt = now
        )

        recipeRepository.saveRecipe(dessert)
        recipeRepository.saveRecipe(dinner)

        turbineScope {
            val turbine = recipeRepository.getRecipesByTag("dessert").testIn(backgroundScope)
            val results = turbine.awaitItem()
            assertEquals(1, results.size)
            assertEquals("Cookies", results[0].name)
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Original HTML
    // -----------------------------------------------------------------------

    @Test
    fun `save recipe with original HTML and retrieve it`() = runTest {
        val recipe = createTestRecipe()
        val html = "<html><body><h1>Cookies</h1></body></html>"
        recipeRepository.saveRecipe(recipe, originalHtml = html)

        val retrieved = recipeRepository.getOriginalHtml(recipe.id)
        assertEquals(html, retrieved)
    }

    @Test
    fun `recipe without original HTML returns null`() = runTest {
        val recipe = createTestRecipe()
        recipeRepository.saveRecipe(recipe)

        val retrieved = recipeRepository.getOriginalHtml(recipe.id)
        assertNull(retrieved)
    }

    // -----------------------------------------------------------------------
    // IDs and names
    // -----------------------------------------------------------------------

    @Test
    fun `getAllRecipeIdsAndNames returns all recipes`() = runTest {
        recipeRepository.saveRecipe(createTestRecipe(id = "r1", name = "Recipe A"))
        recipeRepository.saveRecipe(createTestRecipe(id = "r2", name = "Recipe B"))

        val idsAndNames = recipeRepository.getAllRecipeIdsAndNames()
        assertEquals(2, idsAndNames.size)
        assertTrue(idsAndNames.any { it.id == "r1" && it.name == "Recipe A" })
        assertTrue(idsAndNames.any { it.id == "r2" && it.name == "Recipe B" })
    }
}
