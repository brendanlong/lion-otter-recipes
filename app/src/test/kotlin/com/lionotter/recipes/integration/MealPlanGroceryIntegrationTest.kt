package com.lionotter.recipes.integration

import app.cash.turbine.turbineScope
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Integration tests for the meal plan and grocery list pipeline using
 * real MealPlanRepository + real RecipeRepository backed by real Firestore.
 */
class MealPlanGroceryIntegrationTest : FirestoreIntegrationTest() {

    private val aggregateGroceryListUseCase = AggregateGroceryListUseCase()

    private val now = Instant.fromEpochMilliseconds(1700000000000)
    private val testDate = LocalDate(2024, 1, 15)

    private fun createRecipe(
        id: String,
        name: String,
        ingredients: List<Ingredient>
    ) = Recipe(
        id = id,
        name = name,
        instructionSections = listOf(
            InstructionSection(
                name = null,
                steps = listOf(
                    InstructionStep(
                        stepNumber = 1,
                        instruction = "Combine ingredients.",
                        ingredients = ingredients
                    )
                )
            )
        ),
        tags = emptyList(),
        createdAt = now,
        updatedAt = now
    )

    private fun createMealPlanEntry(
        id: String,
        recipeId: String,
        recipeName: String,
        date: LocalDate = testDate,
        mealType: MealType = MealType.DINNER,
        servings: Double = 1.0
    ) = MealPlanEntry(
        id = id,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeImageUrl = null,
        date = date,
        mealType = mealType,
        servings = servings,
        createdAt = now,
        updatedAt = now
    )

    // -----------------------------------------------------------------------
    // Meal plan date range filtering
    // -----------------------------------------------------------------------

    @Test
    fun `get meal plans for date range filters correctly`() = runTest {
        val jan15 = LocalDate(2024, 1, 15)
        val jan16 = LocalDate(2024, 1, 16)
        val jan20 = LocalDate(2024, 1, 20)

        mealPlanRepository.saveMealPlan(
            createMealPlanEntry(id = "mp-1", recipeId = "r1", recipeName = "Pasta", date = jan15)
        )
        mealPlanRepository.saveMealPlan(
            createMealPlanEntry(id = "mp-2", recipeId = "r2", recipeName = "Salad", date = jan16)
        )
        mealPlanRepository.saveMealPlan(
            createMealPlanEntry(id = "mp-3", recipeId = "r3", recipeName = "Soup", date = jan20)
        )
        pumpLooper()

        turbineScope {
            val turbine = mealPlanRepository.getMealPlansForDateRange(jan15, jan16).testIn(backgroundScope)
            pumpLooper()
            val entries = turbine.awaitItem()
            assertEquals(2, entries.size)
            assertTrue(entries.none { it.recipeName == "Soup" })
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Meal plan update
    // -----------------------------------------------------------------------

    @Test
    fun `update meal plan persists changes`() = runTest {
        val entry = createMealPlanEntry(
            id = "mp-1",
            recipeId = "r1",
            recipeName = "Pasta",
            servings = 2.0
        )
        mealPlanRepository.saveMealPlan(entry)
        pumpLooper()

        val updated = entry.copy(servings = 4.0, updatedAt = Instant.fromEpochMilliseconds(1700000001000))
        mealPlanRepository.updateMealPlan(updated)
        pumpLooper()

        // Verify via date range flow
        turbineScope {
            val turbine = mealPlanRepository.getMealPlansForDateRange(testDate, testDate).testIn(backgroundScope)
            pumpLooper()
            val entries = turbine.awaitItem()
            assertEquals(1, entries.size)
            assertEquals(4.0, entries[0].servings, 0.001)
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Delete meal plans by recipe id
    // -----------------------------------------------------------------------

    @Test
    fun `delete meal plans by recipe id removes all related entries`() {
        mealPlanRepository.saveMealPlan(
            createMealPlanEntry(id = "mp-1", recipeId = "r1", recipeName = "Pasta", date = LocalDate(2024, 1, 15))
        )
        mealPlanRepository.saveMealPlan(
            createMealPlanEntry(id = "mp-2", recipeId = "r1", recipeName = "Pasta", date = LocalDate(2024, 1, 16))
        )
        mealPlanRepository.saveMealPlan(
            createMealPlanEntry(id = "mp-3", recipeId = "r2", recipeName = "Salad", date = LocalDate(2024, 1, 15))
        )
        pumpLooper()

        assertEquals(2, runSuspending { mealPlanRepository.countMealPlansByRecipeId("r1") })

        runSuspending { mealPlanRepository.deleteMealPlansByRecipeId("r1") }
        pumpLooper()

        assertEquals(0, runSuspending { mealPlanRepository.countMealPlansByRecipeId("r1") })
        // Other recipe's meal plans should still exist
        assertEquals(1, runSuspending { mealPlanRepository.countMealPlansByRecipeId("r2") })
    }

    // -----------------------------------------------------------------------
    // Grocery list aggregation (Repository + UseCase integration)
    // -----------------------------------------------------------------------

    @Test
    fun `grocery list aggregates ingredients across multiple recipes`() {
        val pastaRecipe = createRecipe(
            id = "r-pasta",
            name = "Pasta",
            ingredients = listOf(
                Ingredient(name = "olive oil", amount = Amount(value = 2.0, unit = "tbsp")),
                Ingredient(name = "garlic", amount = Amount(value = 3.0, unit = null))
            )
        )
        val saladRecipe = createRecipe(
            id = "r-salad",
            name = "Salad",
            ingredients = listOf(
                Ingredient(name = "olive oil", amount = Amount(value = 1.0, unit = "tbsp")),
                Ingredient(name = "lemon", amount = Amount(value = 1.0, unit = null))
            )
        )

        // Save recipes to repository
        recipeRepository.saveRecipe(pastaRecipe)
        recipeRepository.saveRecipe(saladRecipe)
        pumpLooper()

        // Create meal plan entries
        val pastaEntry = createMealPlanEntry(id = "mp-1", recipeId = "r-pasta", recipeName = "Pasta")
        val saladEntry = createMealPlanEntry(id = "mp-2", recipeId = "r-salad", recipeName = "Salad")
        mealPlanRepository.saveMealPlan(pastaEntry)
        mealPlanRepository.saveMealPlan(saladEntry)
        pumpLooper()

        // Load recipes from the repository (as the real flow would)
        val loadedPasta = runSuspending { recipeRepository.getRecipeByIdOnce("r-pasta") }!!
        val loadedSalad = runSuspending { recipeRepository.getRecipeByIdOnce("r-salad") }!!

        // Aggregate grocery list
        val entriesWithRecipes = listOf(
            pastaEntry to loadedPasta,
            saladEntry to loadedSalad
        )
        val groceryItems = aggregateGroceryListUseCase.execute(entriesWithRecipes)

        // Olive oil should appear as one aggregated item with 2 sources
        val oliveOil = groceryItems.find { it.normalizedName.lowercase() == "olive oil" }
        assertNotNull("Olive oil should be in grocery list", oliveOil)
        assertEquals(2, oliveOil!!.sources.size)

        // Garlic and lemon should be separate items
        assertNotNull(groceryItems.find { it.normalizedName.lowercase() == "garlic" })
        assertNotNull(groceryItems.find { it.normalizedName.lowercase() == "lemon" })
    }

    @Test
    fun `grocery list respects meal plan servings scaling`() {
        val recipe = createRecipe(
            id = "r1",
            name = "Cookies",
            ingredients = listOf(
                Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup"))
            )
        )
        recipeRepository.saveRecipe(recipe)
        pumpLooper()

        // 3x servings
        val entry = createMealPlanEntry(
            id = "mp-1",
            recipeId = "r1",
            recipeName = "Cookies",
            servings = 3.0
        )
        mealPlanRepository.saveMealPlan(entry)
        pumpLooper()

        val loadedRecipe = runSuspending { recipeRepository.getRecipeByIdOnce("r1") }!!
        val groceryItems = aggregateGroceryListUseCase.execute(listOf(entry to loadedRecipe))

        val flour = groceryItems.find { it.normalizedName.lowercase() == "flour" }
        assertNotNull(flour)
        // Verify that the source has the scaling factor applied
        assertEquals(1, flour!!.sources.size)
        assertEquals(3.0, flour.sources[0].scale, 0.001)
    }
}
