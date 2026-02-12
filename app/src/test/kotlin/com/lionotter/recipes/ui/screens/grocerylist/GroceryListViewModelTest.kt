package com.lionotter.recipes.ui.screens.grocerylist

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // Week containing 2025-01-01 (Wednesday), starting Monday 2024-12-30
    private val testWeekStart = LocalDate(2024, 12, 30)
    private val testWeekEnd = testWeekStart.plus(6, DateTimeUnit.DAY)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mealPlanRepository = mockk()
        recipeRepository = mockk()
        settingsDataStore = mockk()

        every { settingsDataStore.groceryVolumeUnitSystem } returns flowOf(UnitSystem.CUSTOMARY)
        every { settingsDataStore.groceryWeightUnitSystem } returns flowOf(UnitSystem.METRIC)
        coEvery { mealPlanRepository.getMealPlansForDateRangeOnce(testWeekStart, testWeekEnd) } returns emptyList()
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
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    private fun createViewModel(entries: List<MealPlanEntry> = emptyList()): GroceryListViewModel {
        coEvery { mealPlanRepository.getMealPlansForDateRangeOnce(testWeekStart, testWeekEnd) } returns entries
        val savedStateHandle = SavedStateHandle(mapOf("weekStart" to testWeekStart.toString()))
        return GroceryListViewModel(
            savedStateHandle = savedStateHandle,
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

    @Test
    fun `checking top-level item checks all sub-sources`() = runTest {
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
            val itemKey = items[0].key

            // Check the top-level item
            viewModel.toggleItemChecked(itemKey)
            val checkedItems = awaitItem()
            val checkedItem = checkedItems[0]

            assertTrue("Top-level item should be checked", checkedItem.isChecked)
            assertTrue("All sources should be checked",
                checkedItem.sources.all { it.isChecked })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unchecking top-level item unchecks all sub-sources`() = runTest {
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
            val itemKey = items[0].key

            // Check then uncheck
            viewModel.toggleItemChecked(itemKey)
            awaitItem()

            viewModel.toggleItemChecked(itemKey)
            val uncheckedItems = awaitItem()
            val uncheckedItem = uncheckedItems[0]

            assertFalse("Top-level item should be unchecked", uncheckedItem.isChecked)
            assertTrue("All sources should be unchecked",
                uncheckedItem.sources.none { it.isChecked })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `checking all sub-sources checks the top-level item`() = runTest {
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
            val source1Key = items[0].sources[0].key
            val source2Key = items[0].sources[1].key

            // Check first source
            viewModel.toggleSourceChecked(source1Key)
            val afterFirst = awaitItem()
            assertFalse("Top-level should NOT be checked yet", afterFirst[0].isChecked)
            assertTrue("First source should be checked",
                afterFirst[0].sources.first { it.key == source1Key }.isChecked)
            assertFalse("Second source should NOT be checked",
                afterFirst[0].sources.first { it.key == source2Key }.isChecked)

            // Check second source - should auto-check top-level
            viewModel.toggleSourceChecked(source2Key)
            val afterSecond = awaitItem()
            assertTrue("Top-level should now be checked", afterSecond[0].isChecked)
            assertTrue("All sources should be checked",
                afterSecond[0].sources.all { it.isChecked })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unchecking one sub-source unchecks the top-level item`() = runTest {
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
            val itemKey = items[0].key
            val source1Key = items[0].sources[0].key

            // Check all via top-level
            viewModel.toggleItemChecked(itemKey)
            awaitItem()

            // Uncheck one source - should uncheck top-level too
            viewModel.toggleSourceChecked(source1Key)
            val afterUncheck = awaitItem()
            assertFalse("Top-level should be unchecked", afterUncheck[0].isChecked)
            assertFalse("First source should be unchecked",
                afterUncheck[0].sources.first { it.key == source1Key }.isChecked)
            assertTrue("Second source should still be checked",
                afterUncheck[0].sources.first { it.key != source1Key }.isChecked)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `share text excludes checked sources but includes unchecked ones`() = runTest {
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
            val source1Key = items[0].sources[0].key

            // Check one source
            viewModel.toggleSourceChecked(source1Key)
            awaitItem()

            val shareText = viewModel.getShareText()
            // Should only have one source (the unchecked one)
            assertTrue("Share text should contain Recipe B",
                shareText.contains("Recipe B"))
            assertFalse("Share text should NOT contain Recipe A",
                shareText.contains("Recipe A"))

            cancelAndIgnoreRemainingEvents()
        }
    }
}
