package com.lionotter.recipes.data.repository

import android.util.Log
import app.cash.turbine.test
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.data.remote.RecipeIdAndName
import com.lionotter.recipes.data.remote.RemoteRecipe
import com.lionotter.recipes.domain.model.Recipe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeRepositoryTest {

    private lateinit var firestoreService: FirestoreService
    private lateinit var recipeRepository: RecipeRepository

    @Before
    fun setup() {
        // Mock Android Log class for unit tests
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        firestoreService = mockk()
        recipeRepository = RecipeRepository(firestoreService)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun createTestRecipe(
        id: String = "test-id",
        name: String = "Test Recipe",
        tags: List<String> = emptyList(),
        isFavorite: Boolean = false
    ) = Recipe(
        id = id,
        name = name,
        tags = tags,
        isFavorite = isFavorite,
        createdAt = Instant.fromEpochMilliseconds(1000L),
        updatedAt = Instant.fromEpochMilliseconds(2000L)
    )

    @Test
    fun `getAllRecipes returns mapped recipes`() = runTest {
        val recipes = listOf(
            RemoteRecipe(createTestRecipe(id = "1", name = "Recipe 1"), null),
            RemoteRecipe(createTestRecipe(id = "2", name = "Recipe 2"), null)
        )
        every { firestoreService.observeRecipes() } returns flowOf(recipes)

        recipeRepository.getAllRecipes().test {
            val result = awaitItem()
            assertEquals(2, result.size)
            assertEquals("Recipe 1", result[0].name)
            assertEquals("Recipe 2", result[1].name)
            awaitComplete()
        }
    }

    @Test
    fun `getAllRecipes returns empty list when no recipes`() = runTest {
        every { firestoreService.observeRecipes() } returns flowOf(emptyList())

        recipeRepository.getAllRecipes().test {
            assertTrue(awaitItem().isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `getRecipeById returns recipe when found`() = runTest {
        val remoteRecipe = RemoteRecipe(createTestRecipe(id = "recipe-1", name = "Found Recipe"), null)
        every { firestoreService.observeRecipeById("recipe-1") } returns flowOf(remoteRecipe)

        recipeRepository.getRecipeById("recipe-1").test {
            val recipe = awaitItem()
            assertNotNull(recipe)
            assertEquals("Found Recipe", recipe?.name)
            awaitComplete()
        }
    }

    @Test
    fun `getRecipeById returns null when not found`() = runTest {
        every { firestoreService.observeRecipeById("nonexistent") } returns flowOf(null)

        recipeRepository.getRecipeById("nonexistent").test {
            assertNull(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getRecipeByIdOnce returns recipe when found`() = runTest {
        val remoteRecipe = RemoteRecipe(createTestRecipe(id = "recipe-1", name = "Found Recipe"), null)
        coEvery { firestoreService.getRecipeById("recipe-1") } returns remoteRecipe

        val result = recipeRepository.getRecipeByIdOnce("recipe-1")

        assertNotNull(result)
        assertEquals("Found Recipe", result?.name)
    }

    @Test
    fun `getRecipeByIdOnce returns null when not found`() = runTest {
        coEvery { firestoreService.getRecipeById("nonexistent") } returns null

        val result = recipeRepository.getRecipeByIdOnce("nonexistent")

        assertNull(result)
    }

    @Test
    fun `getOriginalHtml returns html when present`() = runTest {
        val remoteRecipe = RemoteRecipe(createTestRecipe(), "<html>Test</html>")
        coEvery { firestoreService.getRecipeById("recipe-1") } returns remoteRecipe

        val result = recipeRepository.getOriginalHtml("recipe-1")

        assertEquals("<html>Test</html>", result)
    }

    @Test
    fun `getOriginalHtml returns null when no html`() = runTest {
        val remoteRecipe = RemoteRecipe(createTestRecipe(), null)
        coEvery { firestoreService.getRecipeById("recipe-1") } returns remoteRecipe

        val result = recipeRepository.getOriginalHtml("recipe-1")

        assertNull(result)
    }

    @Test
    fun `getOriginalHtml returns null when recipe not found`() = runTest {
        coEvery { firestoreService.getRecipeById("nonexistent") } returns null

        val result = recipeRepository.getOriginalHtml("nonexistent")

        assertNull(result)
    }

    @Test
    fun `saveRecipe calls firestoreService upsertRecipe`() = runTest {
        coEvery { firestoreService.upsertRecipe(any(), any()) } returns Result.success(Unit)

        val recipe = createTestRecipe(id = "new-recipe", name = "New Recipe")
        recipeRepository.saveRecipe(recipe, "<html>Original</html>")

        coVerify { firestoreService.upsertRecipe(recipe, "<html>Original</html>") }
    }

    @Test
    fun `deleteRecipe calls firestoreService deleteRecipe`() = runTest {
        coEvery { firestoreService.deleteRecipe("recipe-1") } returns Result.success(Unit)

        recipeRepository.deleteRecipe("recipe-1")

        coVerify { firestoreService.deleteRecipe("recipe-1") }
    }

    @Test
    fun `setFavorite calls firestoreService setFavorite`() = runTest {
        coEvery { firestoreService.setFavorite("recipe-1", true) } returns Result.success(Unit)

        recipeRepository.setFavorite("recipe-1", true)

        coVerify { firestoreService.setFavorite("recipe-1", true) }
    }

    @Test
    fun `getAllRecipeIdsAndNames delegates to firestoreService`() = runTest {
        val expected = listOf(
            RecipeIdAndName("1", "Recipe 1"),
            RecipeIdAndName("2", "Recipe 2")
        )
        coEvery { firestoreService.getAllRecipeIdsAndNames() } returns expected

        val result = recipeRepository.getAllRecipeIdsAndNames()

        assertEquals(2, result.size)
        assertEquals("Recipe 1", result[0].name)
    }
}
