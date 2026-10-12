package com.lionotter.recipes.integration

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Verifies the snapshot-listener flows: incremental decoding keeps lists in sync
 * with adds/edits/deletes, and listeners follow auth state changes.
 */
class RepositoryListenerIntegrationTest : FirestoreIntegrationTest() {

    private val now = Instant.fromEpochMilliseconds(1700000000000)
    private val date = LocalDate(2024, 1, 15)

    private fun recipe(id: String, name: String) =
        Recipe(id = id, name = name, createdAt = now, updatedAt = now)

    private fun mealPlan(id: String) = MealPlanEntry(
        id = id,
        recipeId = "r1",
        recipeName = "Recipe",
        recipeImageUrl = null,
        date = date,
        mealType = MealType.DINNER,
        servings = 1.0,
        createdAt = now,
        updatedAt = now
    )

    /** Pumps the looper until an item matching [predicate] arrives. */
    private suspend fun <T> ReceiveTurbine<T>.awaitMatching(predicate: (T) -> Boolean): T {
        while (true) {
            pumpLooper()
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    @Test
    fun `recipe flow reflects add, modify and delete`() = runTest {
        recipeRepository.saveRecipe(recipe("a", "Apple Pie"))
        recipeRepository.saveRecipe(recipe("b", "Banana Bread"))
        pumpLooper()

        turbineScope {
            val turbine = recipeRepository.getAllRecipes().testIn(backgroundScope)
            turbine.awaitMatching { it.size == 2 }

            recipeRepository.setFavorite("a", true)
            recipeRepository.saveRecipe(recipe("b", "Better Banana Bread"))
            val modified = turbine.awaitMatching { list ->
                list.any { it.id == "a" && it.isFavorite } && list.any { it.name == "Better Banana Bread" }
            }
            assertEquals(2, modified.size)

            recipeRepository.deleteRecipe("a")
            val afterDelete = turbine.awaitMatching { it.size == 1 }
            assertEquals("b", afterDelete.single().id)
            assertEquals("Better Banana Bread", afterDelete.single().name)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `meal plan flow follows auth state transitions`() = runTest {
        mealPlanRepository.saveMealPlan(mealPlan("m1"))
        pumpLooper()

        turbineScope {
            val turbine = mealPlanRepository.getMealPlansForDateRange(date, date).testIn(backgroundScope)
            turbine.awaitMatching { it.size == 1 }

            authState.value = AuthState.Loading
            turbine.awaitMatching { it.isEmpty() }

            authState.value = AuthState.Guest(uid = "test-user")
            val restored = turbine.awaitMatching { it.isNotEmpty() }
            assertEquals("m1", restored.single().id)

            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `writes while auth is transitioning are rejected without crashing`() {
        authState.value = AuthState.Loading
        recipeRepository.saveRecipe(recipe("x", "Ignored"))
        mealPlanRepository.saveMealPlan(mealPlan("m-x"))
        pumpLooper()

        authState.value = AuthState.Guest(uid = "test-user")
        assertEquals(null, runSuspending { recipeRepository.getRecipeByIdOnce("x") })
        assertTrue(runSuspending { mealPlanRepository.getAllMealPlansOnce() }.isEmpty())
    }
}
