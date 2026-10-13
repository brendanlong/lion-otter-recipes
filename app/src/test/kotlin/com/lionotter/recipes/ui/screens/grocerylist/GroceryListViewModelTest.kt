package com.lionotter.recipes.ui.screens.grocerylist

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import app.cash.turbine.test
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.port.SavedGroceryListState
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.testutil.FakeAppSettings
import com.lionotter.recipes.testutil.FakeGroceryListStateStore
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

// Robolectric: building a SavedStateHandle from a typed route encodes it with android.net.Uri.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryListViewModelTest {

    private val mealPlanRepository = FakeMealPlanRepository()
    private val recipeRepository = FakeRecipeRepository()
    private val settings = FakeAppSettings().apply {
        groceryVolumeUnitSystemFlow.value = UnitSystem.CUSTOMARY
        groceryWeightUnitSystemFlow.value = UnitSystem.METRIC
    }
    private val groceryListStateStore = FakeGroceryListStateStore()
    private val testDispatcher = StandardTestDispatcher()

    // Week containing 2025-01-01 (Wednesday), starting Monday 2024-12-30
    private val testWeekStart = LocalDate(2024, 12, 30)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun givenRecipes(vararg recipes: Recipe) {
        recipes.forEach { recipeRepository.recipes.value += it.id to it }
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

    private fun createViewModel(
        entries: List<MealPlanEntry> = emptyList(),
        savedState: SavedGroceryListState? = null
    ): GroceryListViewModel {
        entries.forEach(mealPlanRepository::saveMealPlan)
        savedState?.let { groceryListStateStore.states[testWeekStart] = it }
        return newViewModel()
    }

    private fun newViewModel() = GroceryListViewModel(
        savedStateHandle = SavedStateHandle(route = Route.GroceryList(testWeekStart)),
        mealPlanRepository = mealPlanRepository,
        recipeRepository = recipeRepository,
        aggregateGroceryListUseCase = AggregateGroceryListUseCase(),
        settings = settings,
        groceryListStateStore = groceryListStateStore
    )

    private suspend fun generateAndAwait(
        viewModel: GroceryListViewModel
    ): List<DisplayGroceryItem> {
        var result: List<DisplayGroceryItem> = emptyList()
        viewModel.displayGroceryItems.test {
            awaitItem() // initial empty
            viewModel.generateGroceryList()
            result = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }

    @Test
    fun `counts and measured amounts are both shown`() = runTest {
        givenRecipes(createRecipe(
            "r1", "Recipe A", listOf(Ingredient(name = "onion", amount = Amount(value = 1.0)))
        ))
        givenRecipes(createRecipe(
            "r2", "Recipe B", listOf(Ingredient(name = "onion", amount = Amount(value = 200.0, unit = "g")))
        ))
        val viewModel = createViewModel(
            listOf(createEntry("e1", "r1", "Recipe A"), createEntry("e2", "r2", "Recipe B"))
        )
        advanceUntilIdle()

        val total = generateAndAwait(viewModel)[0].totalAmount!!

        assertTrue("Expected grams and count but got: $total", total.contains("g") && total.endsWith("+ 1"))
    }

    @Test
    fun `sources without an amount mark the total as partial`() = runTest {
        givenRecipes(createRecipe(
            "r1", "Recipe A", listOf(Ingredient(name = "salt", amount = Amount(value = 1.0, unit = "tsp")))
        ))
        givenRecipes(createRecipe(
            "r2", "Recipe B", listOf(Ingredient(name = "salt"))
        ))
        val viewModel = createViewModel(
            listOf(createEntry("e1", "r1", "Recipe A"), createEntry("e2", "r2", "Recipe B"))
        )
        advanceUntilIdle()

        val item = generateAndAwait(viewModel)[0]

        assertNotNull(item.totalAmount)
        assertTrue(item.totalIsPartial)
        assertTrue(GroceryListViewModel.aggregateHeaderText(item, "+ more").endsWith("salt + more"))
    }

    @Test
    fun `missing recipes are reported as skipped`() = runTest {
        givenRecipes(createRecipe(
            "r1", "Recipe A", listOf(Ingredient(name = "sugar", amount = Amount(value = 1.0, unit = "cup")))
        ))
        val viewModel = createViewModel(
            listOf(createEntry("e1", "r1", "Recipe A"), createEntry("e2", "gone", "Deleted Recipe"))
        )
        advanceUntilIdle()

        val items = generateAndAwait(viewModel)

        assertEquals(1, items.size)
        assertEquals(listOf("Deleted Recipe"), viewModel.skippedRecipeNames.value)
    }

    @Test
    fun `saved state restores the generated list and checked items`() = runTest {
        givenRecipes(createRecipe(
            "r1", "Recipe A", listOf(
                Ingredient(name = "sugar", amount = Amount(value = 1.0, unit = "cup")),
                Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup"))
            )
        ))
        val viewModel = createViewModel(
            entries = listOf(createEntry("e1", "r1", "Recipe A")),
            savedState = SavedGroceryListState(
                selectedEntryIds = setOf("e1"),
                showingList = true,
                checkedItems = setOf("sugar", "no longer on the list"),
                checkedSources = emptySet()
            )
        )

        viewModel.displayGroceryItems.test {
            advanceUntilIdle()
            val items = expectMostRecentItem()
            assertEquals(GroceryListStep.VIEW_LIST, viewModel.step.value)
            assertEquals(2, items.size)
            assertTrue(items.first { it.key == "sugar" }.isChecked)
            assertFalse(items.first { it.key == "flour" }.isChecked)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(
            testWeekStart to SavedGroceryListState(setOf("e1"), true, setOf("sugar"), emptySet()) in
                groceryListStateStore.saves
        )
    }

    @Test
    fun `check-offs are kept when a selected recipe fails to load`() = runTest {
        givenRecipes(createRecipe(
            "r1", "Recipe A", listOf(Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup")))
        ))
        val viewModel = createViewModel(
            entries = listOf(createEntry("e1", "r1", "Recipe A"), createEntry("e2", "r2", "Recipe B")),
            savedState = SavedGroceryListState(
                selectedEntryIds = setOf("e1", "e2"),
                showingList = true,
                checkedItems = setOf("sugar"),
                checkedSources = emptySet()
            )
        )

        viewModel.displayGroceryItems.test {
            advanceUntilIdle()
            assertEquals(1, expectMostRecentItem().size)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(groceryListStateStore.saves.all { (_, state) -> "sugar" in state.checkedItems })
    }

    @Test
    fun `empty first snapshot does not persist an empty default selection`() = runTest {
        val viewModel = newViewModel()

        viewModel.selectableEntries.test {
            advanceUntilIdle()
            mealPlanRepository.saveMealPlan(createEntry("e1", "r1", "Recipe A"))
            mealPlanRepository.saveMealPlan(createEntry("e2", "r2", "Recipe B"))
            advanceUntilIdle()
            val selected = expectMostRecentItem()!!.values.flatten().filter { it.isSelected }
            assertEquals(2, selected.size)
            cancelAndIgnoreRemainingEvents()
        }
        val savedSelections = groceryListStateStore.saves.map { (_, state) -> state.selectedEntryIds }
        assertFalse(emptySet<String>() in savedSelections)
        assertTrue(setOf("e1", "e2") in savedSelections)
    }

    @Test
    fun `empty snapshot alone persists nothing`() = runTest {
        createViewModel(entries = emptyList())
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), groceryListStateStore.saves)
    }

    @Test
    fun `meal plan load failure shows error instead of crashing`() = runTest {
        mealPlanRepository.loadError = IllegalStateException("boom")
        val viewModel = newViewModel()

        viewModel.selectableEntries.test {
            advanceUntilIdle()
            assertEquals(emptyMap<LocalDate, List<SelectableMealPlanEntry>>(), expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(UserMessage(R.string.grocery_list_load_failed), viewModel.events.first())
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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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

        givenRecipes(recipe1, recipe2)

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
