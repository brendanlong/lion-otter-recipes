package com.lionotter.recipes.data.repository

import android.util.Log
import app.cash.turbine.test
import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.RecipeEntity
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Recipe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeRepositoryTest {

    private lateinit var recipeDao: RecipeDao
    private lateinit var json: Json
    private lateinit var recipeRepository: RecipeRepository

    @Before
    fun setup() {
        // Mock Android Log class for unit tests
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        recipeDao = mockk()
        json = Json { ignoreUnknownKeys = true }
        recipeRepository = RecipeRepository(recipeDao, json)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun createTestEntity(
        id: String = "test-id",
        name: String = "Test Recipe",
        sourceUrl: String? = null,
        story: String? = null,
        servings: Int? = null,
        prepTime: String? = null,
        cookTime: String? = null,
        totalTime: String? = null,
        imageUrl: String? = null,
        tagsJson: String = "[]",
        ingredientSectionsJson: String = "[]",
        instructionSectionsJson: String = "[]",
        originalHtml: String? = null,
        createdAt: Instant = Instant.fromEpochMilliseconds(1000),
        updatedAt: Instant = Instant.fromEpochMilliseconds(2000),
        isFavorite: Boolean = false
    ) = RecipeEntity(
        id = id,
        name = name,
        sourceUrl = sourceUrl,
        story = story,
        servings = servings,
        prepTime = prepTime,
        cookTime = cookTime,
        totalTime = totalTime,
        imageUrl = imageUrl,
        tagsJson = tagsJson,
        ingredientSectionsJson = ingredientSectionsJson,
        instructionSectionsJson = instructionSectionsJson,
        originalHtml = originalHtml,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isFavorite = isFavorite
    )

    private fun createTestRecipe(
        id: String = "test-id",
        name: String = "Test Recipe",
        tags: List<String> = emptyList()
    ) = Recipe(
        id = id,
        name = name,
        tags = tags,
        createdAt = Instant.fromEpochMilliseconds(1000L),
        updatedAt = Instant.fromEpochMilliseconds(2000L)
    )

    @Test
    fun `getAllRecipes returns mapped recipes`() = runTest {
        val entities = listOf(
            createTestEntity(id = "1", name = "Recipe 1"),
            createTestEntity(id = "2", name = "Recipe 2")
        )
        every { recipeDao.getAllRecipes() } returns flowOf(entities)

        recipeRepository.getAllRecipes().test {
            val recipes = awaitItem()
            assertEquals(2, recipes.size)
            assertEquals("Recipe 1", recipes[0].name)
            assertEquals("Recipe 2", recipes[1].name)
            awaitComplete()
        }
    }

    @Test
    fun `getAllRecipes returns empty list when no recipes`() = runTest {
        every { recipeDao.getAllRecipes() } returns flowOf(emptyList())

        recipeRepository.getAllRecipes().test {
            assertTrue(awaitItem().isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `getRecipeById returns mapped recipe when found`() = runTest {
        val entity = createTestEntity(id = "recipe-1", name = "Found Recipe")
        every { recipeDao.getRecipeByIdFlow("recipe-1") } returns flowOf(entity)

        recipeRepository.getRecipeById("recipe-1").test {
            val recipe = awaitItem()
            assertNotNull(recipe)
            assertEquals("Found Recipe", recipe?.name)
            awaitComplete()
        }
    }

    @Test
    fun `getRecipeById returns null when not found`() = runTest {
        every { recipeDao.getRecipeByIdFlow("nonexistent") } returns flowOf(null)

        recipeRepository.getRecipeById("nonexistent").test {
            assertNull(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getRecipeByIdOnce returns recipe when found`() = runTest {
        val entity = createTestEntity(id = "recipe-1", name = "Found Recipe")
        coEvery { recipeDao.getRecipeById("recipe-1") } returns entity

        val result = recipeRepository.getRecipeByIdOnce("recipe-1")

        assertNotNull(result)
        assertEquals("Found Recipe", result?.name)
    }

    @Test
    fun `getRecipeByIdOnce returns null when not found`() = runTest {
        coEvery { recipeDao.getRecipeById("nonexistent") } returns null

        val result = recipeRepository.getRecipeByIdOnce("nonexistent")

        assertNull(result)
    }

    @Test
    fun `getOriginalHtml returns html when present`() = runTest {
        val entity = createTestEntity(originalHtml = "<html>Test</html>")
        coEvery { recipeDao.getRecipeById("recipe-1") } returns entity

        val result = recipeRepository.getOriginalHtml("recipe-1")

        assertEquals("<html>Test</html>", result)
    }

    @Test
    fun `getOriginalHtml returns null when no html`() = runTest {
        val entity = createTestEntity(originalHtml = null)
        coEvery { recipeDao.getRecipeById("recipe-1") } returns entity

        val result = recipeRepository.getOriginalHtml("recipe-1")

        assertNull(result)
    }

    @Test
    fun `getOriginalHtml returns null when recipe not found`() = runTest {
        coEvery { recipeDao.getRecipeById("nonexistent") } returns null

        val result = recipeRepository.getOriginalHtml("nonexistent")

        assertNull(result)
    }

    @Test
    fun `getRecipesByTag returns filtered recipes`() = runTest {
        val entities = listOf(
            createTestEntity(id = "1", name = "Italian Recipe", tagsJson = "[\"italian\"]")
        )
        every { recipeDao.getRecipesByTag("italian") } returns flowOf(entities)

        recipeRepository.getRecipesByTag("italian").test {
            val recipes = awaitItem()
            assertEquals(1, recipes.size)
            assertEquals("Italian Recipe", recipes[0].name)
            awaitComplete()
        }
    }

    @Test
    fun `searchRecipes returns matching recipes`() = runTest {
        val entities = listOf(
            createTestEntity(id = "1", name = "Pasta Carbonara")
        )
        every { recipeDao.searchRecipes("pasta") } returns flowOf(entities)

        recipeRepository.searchRecipes("pasta").test {
            val recipes = awaitItem()
            assertEquals(1, recipes.size)
            assertEquals("Pasta Carbonara", recipes[0].name)
            awaitComplete()
        }
    }

    @Test
    fun `saveRecipe inserts entity into dao`() = runTest {
        val entitySlot = slot<RecipeEntity>()
        coEvery { recipeDao.insertRecipe(capture(entitySlot)) } just runs

        val recipe = createTestRecipe(id = "new-recipe", name = "New Recipe")
        recipeRepository.saveRecipe(recipe, "<html>Original</html>")

        coVerify { recipeDao.insertRecipe(any()) }
        assertEquals("new-recipe", entitySlot.captured.id)
        assertEquals("New Recipe", entitySlot.captured.name)
        assertEquals("<html>Original</html>", entitySlot.captured.originalHtml)
    }

    @Test
    fun `saveRecipe serializes instruction sections with ingredients`() = runTest {
        val entitySlot = slot<RecipeEntity>()
        coEvery { recipeDao.insertRecipe(capture(entitySlot)) } just runs

        val recipe = Recipe(
            id = "recipe-1",
            name = "Test",
            instructionSections = listOf(
                InstructionSection(
                    name = "Main",
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix ingredients",
                            ingredients = listOf(
                                Ingredient(
                                    name = "flour",
                                    amount = Amount(value = 2.0, unit = "cup"),
                                    density = 0.51
                                )
                            )
                        )
                    )
                )
            ),
            createdAt = Instant.fromEpochMilliseconds(1000L),
            updatedAt = Instant.fromEpochMilliseconds(2000L)
        )
        recipeRepository.saveRecipe(recipe)

        assertTrue(entitySlot.captured.instructionSectionsJson.contains("flour"))
        assertTrue(entitySlot.captured.instructionSectionsJson.contains("Main"))
        assertTrue(entitySlot.captured.instructionSectionsJson.contains("Mix ingredients"))
    }

    @Test
    fun `saveRecipe serializes tags`() = runTest {
        val entitySlot = slot<RecipeEntity>()
        coEvery { recipeDao.insertRecipe(capture(entitySlot)) } just runs

        val recipe = createTestRecipe(tags = listOf("italian", "pasta", "dinner"))
        recipeRepository.saveRecipe(recipe)

        assertTrue(entitySlot.captured.tagsJson.contains("italian"))
        assertTrue(entitySlot.captured.tagsJson.contains("pasta"))
        assertTrue(entitySlot.captured.tagsJson.contains("dinner"))
    }

    @Test
    fun `deleteRecipe calls dao deleteRecipe`() = runTest {
        coEvery { recipeDao.deleteRecipe("recipe-1") } just runs

        recipeRepository.deleteRecipe("recipe-1")

        coVerify { recipeDao.deleteRecipe("recipe-1") }
    }

    @Test
    fun `setFavorite calls dao setFavorite`() = runTest {
        coEvery { recipeDao.setFavorite("recipe-1", true) } just runs

        recipeRepository.setFavorite("recipe-1", true)

        coVerify { recipeDao.setFavorite("recipe-1", true) }
    }

    @Test
    fun `entity to recipe mapping preserves all fields`() = runTest {
        val entity = RecipeEntity(
            id = "full-recipe",
            name = "Full Recipe",
            sourceUrl = "https://example.com",
            story = "A great story",
            servings = 4,
            prepTime = "10 min",
            cookTime = "20 min",
            totalTime = "30 min",
            imageUrl = "https://example.com/image.jpg",
            tagsJson = "[\"tag1\",\"tag2\"]",
            ingredientSectionsJson = "[]",
            instructionSectionsJson = "[]",
            originalHtml = null,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
            isFavorite = true
        )
        every { recipeDao.getRecipeByIdFlow("full-recipe") } returns flowOf(entity)

        recipeRepository.getRecipeById("full-recipe").test {
            val recipe = awaitItem()
            assertNotNull(recipe)
            assertEquals("full-recipe", recipe?.id)
            assertEquals("Full Recipe", recipe?.name)
            assertEquals("https://example.com", recipe?.sourceUrl)
            assertEquals("A great story", recipe?.story)
            assertEquals(4, recipe?.servings)
            assertEquals("10 min", recipe?.prepTime)
            assertEquals("20 min", recipe?.cookTime)
            assertEquals("30 min", recipe?.totalTime)
            assertEquals("https://example.com/image.jpg", recipe?.imageUrl)
            assertEquals(listOf("tag1", "tag2"), recipe?.tags)
            assertEquals(true, recipe?.isFavorite)
            awaitComplete()
        }
    }

    @Test
    fun `entity to recipe mapping handles malformed instruction json`() = runTest {
        val entity = createTestEntity(instructionSectionsJson = "not valid json")
        every { recipeDao.getRecipeByIdFlow("recipe-1") } returns flowOf(entity)

        recipeRepository.getRecipeById("recipe-1").test {
            val recipe = awaitItem()
            assertNotNull(recipe)
            assertTrue(recipe?.instructionSections?.isEmpty() == true)
            awaitComplete()
        }
    }
}
