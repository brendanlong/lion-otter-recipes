package com.lionotter.recipes.domain.usecase

import app.cash.turbine.test
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GetRecipeByIdUseCaseTest {

    private lateinit var recipeRepository: RecipeRepository
    private lateinit var getRecipeByIdUseCase: GetRecipeByIdUseCase

    @Before
    fun setup() {
        recipeRepository = mockk()
        getRecipeByIdUseCase = GetRecipeByIdUseCase(recipeRepository)
    }

    private fun createTestRecipe(id: String, name: String) = Recipe(
        id = id,
        name = name,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    @Test
    fun `execute returns flow with recipe when found`() = runTest {
        val recipe = createTestRecipe("recipe-1", "Test Recipe")
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(recipe)

        getRecipeByIdUseCase.execute("recipe-1").test {
            assertEquals(recipe, awaitItem())
            awaitComplete()
        }

        verify { recipeRepository.getRecipeById("recipe-1") }
    }

    @Test
    fun `execute returns flow with null when recipe not found`() = runTest {
        every { recipeRepository.getRecipeById("nonexistent") } returns flowOf(null)

        getRecipeByIdUseCase.execute("nonexistent").test {
            assertNull(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `executeOnce returns recipe when found`() = runTest {
        val recipe = createTestRecipe("recipe-1", "Test Recipe")
        coEvery { recipeRepository.getRecipeByIdOnce("recipe-1") } returns recipe

        val result = getRecipeByIdUseCase.executeOnce("recipe-1")

        assertEquals(recipe, result)
        coVerify { recipeRepository.getRecipeByIdOnce("recipe-1") }
    }

    @Test
    fun `executeOnce returns null when recipe not found`() = runTest {
        coEvery { recipeRepository.getRecipeByIdOnce("nonexistent") } returns null

        val result = getRecipeByIdUseCase.executeOnce("nonexistent")

        assertNull(result)
    }

    @Test
    fun `execute uses correct recipe id`() = runTest {
        val recipeId = "specific-id-123"
        val recipe = createTestRecipe(recipeId, "Specific Recipe")
        every { recipeRepository.getRecipeById(recipeId) } returns flowOf(recipe)

        getRecipeByIdUseCase.execute(recipeId).test {
            val result = awaitItem()
            assertEquals(recipeId, result?.id)
            awaitComplete()
        }
    }

    @Test
    fun `executeOnce uses correct recipe id`() = runTest {
        val recipeId = "specific-id-456"
        val recipe = createTestRecipe(recipeId, "Another Recipe")
        coEvery { recipeRepository.getRecipeByIdOnce(recipeId) } returns recipe

        val result = getRecipeByIdUseCase.executeOnce(recipeId)

        assertEquals(recipeId, result?.id)
    }
}
