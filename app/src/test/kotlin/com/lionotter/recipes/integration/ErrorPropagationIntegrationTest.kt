package com.lionotter.recipes.integration

import app.cash.turbine.turbineScope
import com.lionotter.recipes.data.local.RecipeEntity
import com.lionotter.recipes.data.repository.RepositoryError
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Integration tests verifying that errors in the data layer (malformed JSON,
 * missing data) are properly handled and surfaced rather than silently swallowed.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class ErrorPropagationIntegrationTest : HiltIntegrationTest() {

    private val now = Instant.fromEpochMilliseconds(1700000000000)

    // -----------------------------------------------------------------------
    // Malformed JSON handling
    // -----------------------------------------------------------------------

    @Test
    fun `recipe with malformed instruction JSON returns empty instructions`() = runTest {
        // Insert entity directly with malformed JSON via DAO (bypassing repository serialization)
        val entity = RecipeEntity(
            id = "bad-instructions",
            name = "Broken Recipe",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "{this is not valid json!!!}",
            equipmentJson = "[]",
            tagsJson = "[]",
            imageUrl = null,
            originalHtml = null,
            createdAt = now,
            updatedAt = now,
            isFavorite = false
        )
        recipeDao.insertRecipe(entity)

        // Repository should handle the parse error gracefully
        val recipe = recipeRepository.getRecipeByIdOnce("bad-instructions")
        assertNotNull("Recipe should still be returned despite malformed JSON", recipe)
        assertEquals("Broken Recipe", recipe!!.name)
        assertTrue("Instructions should be empty when JSON is malformed", recipe.instructionSections.isEmpty())
    }

    @Test
    fun `recipe with malformed tags JSON returns empty tags`() = runTest {
        val entity = RecipeEntity(
            id = "bad-tags",
            name = "Bad Tags Recipe",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "[]",
            equipmentJson = "[]",
            tagsJson = "not a json array",
            imageUrl = null,
            originalHtml = null,
            createdAt = now,
            updatedAt = now,
            isFavorite = false
        )
        recipeDao.insertRecipe(entity)

        val recipe = recipeRepository.getRecipeByIdOnce("bad-tags")
        assertNotNull(recipe)
        assertTrue("Tags should be empty when JSON is malformed", recipe!!.tags.isEmpty())
    }

    @Test
    fun `recipe with malformed equipment JSON returns empty equipment`() = runTest {
        val entity = RecipeEntity(
            id = "bad-equipment",
            name = "Bad Equipment Recipe",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "[]",
            equipmentJson = "{{broken}}",
            tagsJson = "[]",
            imageUrl = null,
            originalHtml = null,
            createdAt = now,
            updatedAt = now,
            isFavorite = false
        )
        recipeDao.insertRecipe(entity)

        val recipe = recipeRepository.getRecipeByIdOnce("bad-equipment")
        assertNotNull(recipe)
        assertTrue("Equipment should be empty when JSON is malformed", recipe!!.equipment.isEmpty())
    }

    @Test
    fun `repository emits parse error for malformed JSON via getRecipeByIdOnce`() = runTest {
        val entity = RecipeEntity(
            id = "error-emit-test",
            name = "Error Recipe",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "{invalid}",
            equipmentJson = "[]",
            tagsJson = "[]",
            imageUrl = null,
            originalHtml = null,
            createdAt = now,
            updatedAt = now,
            isFavorite = false
        )
        recipeDao.insertRecipe(entity)

        turbineScope {
            val errorTurbine = recipeRepository.errors.testIn(backgroundScope)

            // Trigger error-reporting code path
            recipeRepository.getRecipeByIdOnce("error-emit-test")

            val error = errorTurbine.awaitItem()
            assertTrue(error is RepositoryError.ParseError)
            val parseError = error as RepositoryError.ParseError
            assertEquals("error-emit-test", parseError.recipeId)
            assertEquals("Error Recipe", parseError.recipeName)
            assertTrue(parseError.failedFields.contains("instructions"))

            errorTurbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recipe with all malformed JSON fields emits error with all failed fields`() = runTest {
        val entity = RecipeEntity(
            id = "all-broken",
            name = "All Broken",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "BAD",
            equipmentJson = "BAD",
            tagsJson = "BAD",
            imageUrl = null,
            originalHtml = null,
            createdAt = now,
            updatedAt = now,
            isFavorite = false
        )
        recipeDao.insertRecipe(entity)

        turbineScope {
            val errorTurbine = recipeRepository.errors.testIn(backgroundScope)

            val recipe = recipeRepository.getRecipeByIdOnce("all-broken")
            assertNotNull(recipe)
            assertTrue(recipe!!.instructionSections.isEmpty())
            assertTrue(recipe.equipment.isEmpty())
            assertTrue(recipe.tags.isEmpty())

            val error = errorTurbine.awaitItem()
            assertTrue(error is RepositoryError.ParseError)
            val parseError = error as RepositoryError.ParseError
            assertEquals(3, parseError.failedFields.size)
            assertTrue(parseError.failedFields.contains("instructions"))
            assertTrue(parseError.failedFields.contains("equipment"))
            assertTrue(parseError.failedFields.contains("tags"))

            errorTurbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Flow-based error handling
    // -----------------------------------------------------------------------

    @Test
    fun `malformed recipe in getAllRecipes flow returns recipe with empty fields`() = runTest {
        // Insert a good recipe and a bad recipe
        val goodEntity = RecipeEntity(
            id = "good-recipe",
            name = "Good Recipe",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "[]",
            equipmentJson = "[]",
            tagsJson = """["good"]""",
            imageUrl = null,
            originalHtml = null,
            createdAt = now,
            updatedAt = now,
            isFavorite = false
        )
        val badEntity = RecipeEntity(
            id = "bad-recipe",
            name = "Bad Recipe",
            sourceUrl = null,
            story = null,
            servings = null,
            prepTime = null,
            cookTime = null,
            totalTime = null,
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "INVALID",
            equipmentJson = "[]",
            tagsJson = "[]",
            imageUrl = null,
            originalHtml = null,
            createdAt = now,
            updatedAt = Instant.fromEpochMilliseconds(1700000001000),
            isFavorite = false
        )

        recipeDao.insertRecipe(goodEntity)
        recipeDao.insertRecipe(badEntity)

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            val recipes = turbine.awaitItem()

            // Both recipes should be returned
            assertEquals(2, recipes.size)

            // The bad recipe should have empty instructions but still be present
            val badRecipe = recipes.find { it.id == "bad-recipe" }
            assertNotNull(badRecipe)
            assertTrue(badRecipe!!.instructionSections.isEmpty())

            // The good recipe should be intact
            val goodRecipe = recipes.find { it.id == "good-recipe" }
            assertNotNull(goodRecipe)
            assertEquals(listOf("good"), goodRecipe!!.tags)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Non-existent recipe handling
    // -----------------------------------------------------------------------

    @Test
    fun `get non-existent recipe returns null`() = runTest {
        val result = recipeRepository.getRecipeByIdOnce("non-existent-id")
        assertEquals(null, result)
    }

    @Test
    fun `get original HTML for non-existent recipe returns null`() = runTest {
        val result = recipeRepository.getOriginalHtml("non-existent-id")
        assertEquals(null, result)
    }
}
