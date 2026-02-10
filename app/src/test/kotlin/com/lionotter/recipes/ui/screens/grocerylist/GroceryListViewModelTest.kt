package com.lionotter.recipes.ui.screens.grocerylist

import app.cash.turbine.test
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroceryListViewModelTest {

    private lateinit var mealPlanRepository: MealPlanRepository
    private lateinit var recipeRepository: RecipeRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mealPlanRepository = mockk()
        recipeRepository = mockk()
        settingsDataStore = mockk()

        every { settingsDataStore.volumeUnitSystem } returns flowOf(UnitSystem.CUSTOMARY)
        every { settingsDataStore.weightUnitSystem } returns flowOf(UnitSystem.METRIC)
        coEvery { mealPlanRepository.getAllMealPlansOnce() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
                        instruction = "Combine ingredients",
                        ingredients = ingredients
                    )
                )
            )
        ),
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    private fun createEntry(
        id: String,
        recipeId: String,
        recipeName: String,
        servings: Double = 1.0
    ) = MealPlanEntry(
        id = id,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeImageUrl = null,
        date = LocalDate(2025, 1, 1),
        mealType = MealType.DINNER,
        servings = servings,
        createdAt = 1000,
        updatedAt = 2000
    )

    private fun createViewModel(entries: List<MealPlanEntry> = emptyList()): GroceryListViewModel {
        coEvery { mealPlanRepository.getAllMealPlansOnce() } returns entries
        return GroceryListViewModel(
            mealPlanRepository = mealPlanRepository,
            recipeRepository = recipeRepository,
            aggregateGroceryListUseCase = AggregateGroceryListUseCase(),
            settingsDataStore = settingsDataStore
        )
    }

    @Test
    fun `same unit category sources are summed correctly`() = runTest {
        val recipe1 = createRecipe(
            id = "r1",
            name = "Recipe A",
            ingredients = listOf(
                Ingredient(name = "sugar", amount = Amount(value = 2.0, unit = "tbsp"))
            )
        )
        val recipe2 = createRecipe(
            id = "r2",
            name = "Recipe B",
            ingredients = listOf(
                Ingredient(name = "sugar", amount = Amount(value = 3.0, unit = "tbsp"))
            )
        )

        val entry1 = createEntry("e1", "r1", "Recipe A")
        val entry2 = createEntry("e2", "r2", "Recipe B")

        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe1
        coEvery { recipeRepository.getRecipeByIdOnce("r2") } returns recipe2

        val viewModel = createViewModel(listOf(entry1, entry2))
        advanceUntilIdle()

        viewModel.displayGroceryItems.test {
            awaitItem() // initial empty

            viewModel.generateGroceryList()
            val items = awaitItem()

            assertEquals(1, items.size)
            val sugarItem = items[0]
            assertEquals(2, sugarItem.sources.size)
            // 2 + 3 = 5 tbsp = 73.935 mL → best unit selection may pick 15 tsp
            assertNotNull(sugarItem.totalAmount)
            assertTrue(
                "Expected volume unit in total but got: ${sugarItem.totalAmount}",
                sugarItem.totalAmount!!.let { it.contains("tbsp") || it.contains("tsp") }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mixed weight and volume sources are converted to weight`() = runTest {
        // Sugar density: ~0.85 g/mL
        val sugarDensity = 0.85

        val recipe1 = createRecipe(
            id = "r1",
            name = "Recipe A",
            ingredients = listOf(
                Ingredient(
                    name = "sugar",
                    amount = Amount(value = 11.0, unit = "tbsp"),
                    density = sugarDensity
                )
            )
        )
        val recipe2 = createRecipe(
            id = "r2",
            name = "Recipe B",
            ingredients = listOf(
                Ingredient(
                    name = "sugar",
                    amount = Amount(value = 10.0, unit = "oz"),
                    density = sugarDensity
                )
            )
        )

        val entry1 = createEntry("e1", "r1", "Recipe A")
        val entry2 = createEntry("e2", "r2", "Recipe B")

        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe1
        coEvery { recipeRepository.getRecipeByIdOnce("r2") } returns recipe2

        val viewModel = createViewModel(listOf(entry1, entry2))
        advanceUntilIdle()

        viewModel.displayGroceryItems.test {
            awaitItem() // initial empty

            viewModel.generateGroceryList()
            val items = awaitItem()

            assertEquals(1, items.size)
            val sugarItem = items[0]
            assertEquals(2, sugarItem.sources.size)
            // Should have a total in weight (grams) because categories are mixed
            // 11 tbsp = 11 * 14.787 mL = 162.657 mL → 162.657 * 0.85 = 138.26 g
            // 10 oz = 10 * 28.3495 = 283.495 g
            // Total ≈ 421.75 g
            assertNotNull(sugarItem.totalAmount)
            assertTrue(
                "Expected total in grams but got: ${sugarItem.totalAmount}",
                sugarItem.totalAmount!!.contains("g")
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mixed categories uses shared density for sources missing it`() = runTest {
        val sugarDensity = 0.85

        val recipe1 = createRecipe(
            id = "r1",
            name = "Recipe A",
            ingredients = listOf(
                Ingredient(
                    name = "sugar",
                    amount = Amount(value = 5.0, unit = "tbsp")
                    // No density on this source
                )
            )
        )
        val recipe2 = createRecipe(
            id = "r2",
            name = "Recipe B",
            ingredients = listOf(
                Ingredient(
                    name = "sugar",
                    amount = Amount(value = 10.0, unit = "oz"),
                    density = sugarDensity
                )
            )
        )

        val entry1 = createEntry("e1", "r1", "Recipe A")
        val entry2 = createEntry("e2", "r2", "Recipe B")

        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe1
        coEvery { recipeRepository.getRecipeByIdOnce("r2") } returns recipe2

        val viewModel = createViewModel(listOf(entry1, entry2))
        advanceUntilIdle()

        viewModel.displayGroceryItems.test {
            awaitItem() // initial empty

            viewModel.generateGroceryList()
            val items = awaitItem()

            assertEquals(1, items.size)
            val sugarItem = items[0]
            // Both sources should be converted to weight using shared density
            // 5 tbsp = 5 * 14.787 mL = 73.935 mL → 73.935 * 0.85 = 62.84 g
            // 10 oz = 283.495 g
            // Total ≈ 346.34 g (both included, not just the weight source)
            assertNotNull(sugarItem.totalAmount)
            assertTrue(
                "Expected total in grams but got: ${sugarItem.totalAmount}",
                sugarItem.totalAmount!!.contains("g")
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mixed categories without any density skips volume sources`() = runTest {
        val recipe1 = createRecipe(
            id = "r1",
            name = "Recipe A",
            ingredients = listOf(
                Ingredient(
                    name = "sugar",
                    amount = Amount(value = 5.0, unit = "tbsp")
                )
            )
        )
        val recipe2 = createRecipe(
            id = "r2",
            name = "Recipe B",
            ingredients = listOf(
                Ingredient(
                    name = "sugar",
                    amount = Amount(value = 10.0, unit = "oz")
                )
            )
        )

        val entry1 = createEntry("e1", "r1", "Recipe A")
        val entry2 = createEntry("e2", "r2", "Recipe B")

        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe1
        coEvery { recipeRepository.getRecipeByIdOnce("r2") } returns recipe2

        val viewModel = createViewModel(listOf(entry1, entry2))
        advanceUntilIdle()

        viewModel.displayGroceryItems.test {
            awaitItem() // initial empty

            viewModel.generateGroceryList()
            val items = awaitItem()

            assertEquals(1, items.size)
            val sugarItem = items[0]
            // No density anywhere — volume can't be converted, only weight portion
            // 10 oz = 283.495 g
            assertNotNull(sugarItem.totalAmount)
            assertTrue(
                "Expected total in grams but got: ${sugarItem.totalAmount}",
                sugarItem.totalAmount!!.contains("g")
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `single source does not show total amount`() = runTest {
        val recipe1 = createRecipe(
            id = "r1",
            name = "Recipe A",
            ingredients = listOf(
                Ingredient(name = "sugar", amount = Amount(value = 2.0, unit = "cup"))
            )
        )

        val entry1 = createEntry("e1", "r1", "Recipe A")

        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe1

        val viewModel = createViewModel(listOf(entry1))
        advanceUntilIdle()

        viewModel.displayGroceryItems.test {
            awaitItem() // initial empty

            viewModel.generateGroceryList()
            val items = awaitItem()

            assertEquals(1, items.size)
            assertNull(items[0].totalAmount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `count items are summed correctly`() = runTest {
        val recipe1 = createRecipe(
            id = "r1",
            name = "Recipe A",
            ingredients = listOf(
                Ingredient(name = "eggs", amount = Amount(value = 3.0))
            )
        )
        val recipe2 = createRecipe(
            id = "r2",
            name = "Recipe B",
            ingredients = listOf(
                Ingredient(name = "eggs", amount = Amount(value = 2.0))
            )
        )

        val entry1 = createEntry("e1", "r1", "Recipe A")
        val entry2 = createEntry("e2", "r2", "Recipe B")

        coEvery { recipeRepository.getRecipeByIdOnce("r1") } returns recipe1
        coEvery { recipeRepository.getRecipeByIdOnce("r2") } returns recipe2

        val viewModel = createViewModel(listOf(entry1, entry2))
        advanceUntilIdle()

        viewModel.displayGroceryItems.test {
            awaitItem() // initial empty

            viewModel.generateGroceryList()
            val items = awaitItem()

            assertEquals(1, items.size)
            assertEquals("5", items[0].totalAmount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
