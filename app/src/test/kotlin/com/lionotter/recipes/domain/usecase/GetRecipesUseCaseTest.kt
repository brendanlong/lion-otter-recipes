package com.lionotter.recipes.domain.usecase

import app.cash.turbine.test
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Recipe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GetRecipesUseCaseTest {

    private lateinit var recipeRepository: RecipeRepository
    private lateinit var getRecipesUseCase: GetRecipesUseCase

    @Before
    fun setup() {
        recipeRepository = mockk()
        getRecipesUseCase = GetRecipesUseCase(recipeRepository)
    }

    private fun createTestRecipe(
        id: String,
        name: String,
        tags: List<String> = emptyList()
    ) = Recipe(
        id = id,
        name = name,
        tags = tags,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    @Test
    fun `execute returns flow from repository`() = runTest {
        val recipes = listOf(
            createTestRecipe("1", "Pasta"),
            createTestRecipe("2", "Pizza")
        )
        every { recipeRepository.getAllRecipes() } returns flowOf(recipes)

        getRecipesUseCase.execute().test {
            assertEquals(recipes, awaitItem())
            awaitComplete()
        }

        verify { recipeRepository.getAllRecipes() }
    }

    @Test
    fun `execute returns empty list when no recipes exist`() = runTest {
        every { recipeRepository.getAllRecipes() } returns flowOf(emptyList())

        getRecipesUseCase.execute().test {
            assertEquals(emptyList<Recipe>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `byTag delegates to repository getRecipesByTag`() = runTest {
        val tag = "italian"
        val recipes = listOf(
            createTestRecipe("1", "Pasta", listOf("italian")),
            createTestRecipe("2", "Risotto", listOf("italian"))
        )
        every { recipeRepository.getRecipesByTag(tag) } returns flowOf(recipes)

        getRecipesUseCase.byTag(tag).test {
            assertEquals(recipes, awaitItem())
            awaitComplete()
        }

        verify { recipeRepository.getRecipesByTag(tag) }
    }

    @Test
    fun `byTag returns empty list for non-existent tag`() = runTest {
        val tag = "nonexistent"
        every { recipeRepository.getRecipesByTag(tag) } returns flowOf(emptyList())

        getRecipesUseCase.byTag(tag).test {
            assertEquals(emptyList<Recipe>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `search delegates to repository searchRecipes`() = runTest {
        val query = "pasta"
        val recipes = listOf(
            createTestRecipe("1", "Creamy Pasta")
        )
        every { recipeRepository.searchRecipes(query) } returns flowOf(recipes)

        getRecipesUseCase.search(query).test {
            assertEquals(recipes, awaitItem())
            awaitComplete()
        }

        verify { recipeRepository.searchRecipes(query) }
    }

    @Test
    fun `search returns empty list for no matches`() = runTest {
        val query = "nonexistent"
        every { recipeRepository.searchRecipes(query) } returns flowOf(emptyList())

        getRecipesUseCase.search(query).test {
            assertEquals(emptyList<Recipe>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `search handles empty query`() = runTest {
        val query = ""
        val allRecipes = listOf(
            createTestRecipe("1", "Pasta"),
            createTestRecipe("2", "Pizza")
        )
        every { recipeRepository.searchRecipes(query) } returns flowOf(allRecipes)

        getRecipesUseCase.search(query).test {
            assertEquals(allRecipes, awaitItem())
            awaitComplete()
        }
    }
}
