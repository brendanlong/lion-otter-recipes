package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Instant

class DeleteRecipeUseCaseTest {
    private fun recipe(id: String, imageUrl: String?) = Recipe(
        id = id,
        name = id,
        imageUrl = imageUrl,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    private val recipes = FakeRecipeRepository(recipe("r1", "gs://bucket/shared.jpg"), recipe("r2", "gs://bucket/shared.jpg"))
    private val mealPlans = FakeMealPlanRepository(
        MealPlanEntry(
            id = "m1", recipeId = "r1", recipeName = "r1", recipeImageUrl = null, date = LocalDate(2024, 1, 1),
            mealType = MealType.LUNCH, createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0)
        )
    )
    private val imageStore = FakeImageStore()
    private val deleteRecipe = DeleteRecipeUseCase(recipes, mealPlans, imageStore)

    @Test
    fun `deletes the recipe and its meal plans and releases its image`() = runTest {
        deleteRecipe("r1")

        assertEquals(listOf("r1"), recipes.deleted)
        assertTrue(mealPlans.entries.value.isEmpty())
        // The image store decides whether the image is still used (here by r2)
        assertEquals(listOf("gs://bucket/shared.jpg" to "r1"), imageStore.released)
    }

    @Test
    fun `nothing is deleted if the meal plans can't be found`() = runTest {
        mealPlans.deleteByRecipeError = IOException("offline")

        val result = runCatching { deleteRecipe("r1") }

        assertTrue(result.isFailure)
        assertTrue(recipes.deleted.isEmpty())
        assertTrue(imageStore.released.isEmpty())
    }

    @Test
    fun `the recipe is still deleted if its image can't be read`() = runTest {
        recipes.imageQueryError = IOException("cache unavailable")

        deleteRecipe("r1")

        assertEquals(listOf("r1"), recipes.deleted)
        assertTrue(imageStore.released.isEmpty())
    }

    @Test
    fun `a recipe without an image releases nothing`() = runTest {
        recipes.saveRecipe(recipe("r3", null))

        deleteRecipe("r3")

        assertEquals(listOf("r3"), recipes.deleted)
        assertTrue(imageStore.released.isEmpty())
    }
}
