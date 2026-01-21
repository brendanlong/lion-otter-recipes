package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.data.repository.RecipeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class DeleteRecipeUseCaseTest {

    private lateinit var recipeRepository: RecipeRepository
    private lateinit var deleteRecipeUseCase: DeleteRecipeUseCase

    @Before
    fun setup() {
        recipeRepository = mockk()
        deleteRecipeUseCase = DeleteRecipeUseCase(recipeRepository)
    }

    @Test
    fun `execute delegates to repository deleteRecipe`() = runTest {
        val recipeId = "recipe-123"
        coEvery { recipeRepository.deleteRecipe(recipeId) } just runs

        deleteRecipeUseCase.execute(recipeId)

        coVerify { recipeRepository.deleteRecipe(recipeId) }
    }

    @Test
    fun `execute calls repository with correct id`() = runTest {
        val recipeId = "specific-recipe-id"
        coEvery { recipeRepository.deleteRecipe(recipeId) } just runs

        deleteRecipeUseCase.execute(recipeId)

        coVerify(exactly = 1) { recipeRepository.deleteRecipe(recipeId) }
    }

    @Test
    fun `execute can be called multiple times with different ids`() = runTest {
        coEvery { recipeRepository.deleteRecipe(any()) } just runs

        deleteRecipeUseCase.execute("recipe-1")
        deleteRecipeUseCase.execute("recipe-2")
        deleteRecipeUseCase.execute("recipe-3")

        coVerify { recipeRepository.deleteRecipe("recipe-1") }
        coVerify { recipeRepository.deleteRecipe("recipe-2") }
        coVerify { recipeRepository.deleteRecipe("recipe-3") }
    }
}
