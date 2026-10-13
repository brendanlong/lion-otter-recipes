package com.lionotter.recipes.ui.screens.grocerylist

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
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
import com.lionotter.recipes.testutil.FakeAppSettings
import com.lionotter.recipes.testutil.FakeGroceryListStateStore
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun createRecipe(id: String, name: String, vararg ingredients: Ingredient) = Recipe(
        id = id,
        name = name,
        instructionSections = listOf(
            InstructionSection(
                name = null,
                steps = listOf(
                    InstructionStep(stepNumber = 1, instruction = "Combine ingredients", ingredients = ingredients.toList())
                )
            )
        ),
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    private fun createEntry(id: String, recipeId: String, recipeName: String) = MealPlanEntry(
        id = id,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeImageUrl = null,
        date = LocalDate(2025, 1, 1),
        mealType = MealType.DINNER,
        servings = 1.0,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    private fun sugar(cups: Double) = Ingredient(name = "sugar", amount = Amount(value = cups, unit = "cup"))
    private fun flour(cups: Double) = Ingredient(name = "flour", amount = Amount(value = cups, unit = "cup"))

    private fun createViewModel(
        entries: List<MealPlanEntry> = emptyList(),
        savedState: SavedGroceryListState? = null
    ): GroceryListViewModel {
        entries.forEach(mealPlanRepository::saveMealPlan)
        savedState?.let { groceryListStateStore.states[testWeekStart] = it }
        return GroceryListViewModel(
            savedStateHandle = SavedStateHandle(route = Route.GroceryList(testWeekStart)),
            mealPlanRepository = mealPlanRepository,
            recipeRepository = recipeRepository,
            aggregateGroceryListUseCase = AggregateGroceryListUseCase(),
            settings = settings,
            groceryListStateStore = groceryListStateStore
        )
    }

    /** Keeps [GroceryListViewModel.uiState] subscribed, as the screen does. */
    private fun TestScope.observe(viewModel: GroceryListViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
    }

    private val GroceryListViewModel.list get() = uiState.value as GroceryListUiState.ViewList
    private val GroceryListViewModel.selection get() = uiState.value as GroceryListUiState.SelectRecipes

    private fun TestScope.generate(viewModel: GroceryListViewModel): List<DisplayGroceryItem> {
        viewModel.generateGroceryList()
        advanceUntilIdle()
        return viewModel.list.items!!
    }

    @Test
    fun `first visit selects every meal`() = runTest {
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A"), createEntry("e2", "r2", "B")))
        observe(viewModel)

        assertEquals(2, viewModel.selection.entriesByDate.values.flatten().count { it.isSelected })
        assertTrue(viewModel.selection.hasSelection)
    }

    @Test
    fun `empty first snapshot does not persist an empty default selection`() = runTest {
        val viewModel = createViewModel()
        observe(viewModel)
        assertEquals(emptyMap<LocalDate, List<SelectableMealPlanEntry>>(), viewModel.selection.entriesByDate)

        mealPlanRepository.saveMealPlan(createEntry("e1", "r1", "A"))
        mealPlanRepository.saveMealPlan(createEntry("e2", "r2", "B"))
        advanceUntilIdle()

        assertEquals(2, viewModel.selection.entriesByDate.values.flatten().count { it.isSelected })
        val savedSelections = groceryListStateStore.saves.map { (_, state) -> state.selectedEntryIds }
        assertFalse(emptySet<String>() in savedSelections)
        assertTrue(setOf("e1", "e2") in savedSelections)
    }

    @Test
    fun `empty snapshot alone persists nothing`() = runTest {
        observe(createViewModel())
        assertEquals(emptyList<Any>(), groceryListStateStore.saves)
    }

    @Test
    fun `meal plan load failure is shown as state`() = runTest {
        mealPlanRepository.loadError = IllegalStateException("boom")
        val viewModel = createViewModel()
        observe(viewModel)

        assertTrue(viewModel.selection.loadFailed)
    }

    @Test
    fun `selection changes are persisted`() = runTest {
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A"), createEntry("e2", "r2", "B")))
        observe(viewModel)

        viewModel.setEntrySelected("e1", selected = false)
        advanceUntilIdle()
        assertEquals(setOf("e2"), groceryListStateStore.states[testWeekStart]!!.selectedEntryIds)

        viewModel.deselectAll()
        advanceUntilIdle()
        assertFalse(viewModel.selection.hasSelection)

        viewModel.selectAll()
        advanceUntilIdle()
        assertEquals(setOf("e1", "e2"), groceryListStateStore.states[testWeekStart]!!.selectedEntryIds)
    }

    @Test
    fun `generating shows the selected meals' ingredients and back returns to the selection`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0)), createRecipe("r2", "B", flour(2.0)))
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A"), createEntry("e2", "r2", "B")))
        observe(viewModel)
        viewModel.setEntrySelected("e2", selected = false)

        assertEquals(listOf("sugar"), generate(viewModel).map { it.key })
        assertTrue(groceryListStateStore.states[testWeekStart]!!.showingList)

        viewModel.backToSelection()
        advanceUntilIdle()
        assertFalse(viewModel.selection.entriesByDate.isEmpty())
        assertFalse(groceryListStateStore.states[testWeekStart]!!.showingList)
    }

    @Test
    fun `missing recipes are reported as skipped and fill in once they exist`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0)))
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A"), createEntry("e2", "r2", "Deleted Recipe")))
        observe(viewModel)

        assertEquals(1, generate(viewModel).size)
        assertEquals(listOf("Deleted Recipe"), viewModel.list.skippedRecipeNames)

        givenRecipes(createRecipe("r2", "Deleted Recipe", flour(1.0)))
        advanceUntilIdle()
        assertEquals(2, viewModel.list.items!!.size)
        assertEquals(emptyList<String>(), viewModel.list.skippedRecipeNames)
    }

    @Test
    fun `editing a recipe after generating updates the list and keeps check-offs`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0), flour(2.0)))
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A")))
        observe(viewModel)
        val sugarItem = generate(viewModel).first { it.key == "sugar" }
        viewModel.setItemChecked(sugarItem, checked = true)
        advanceUntilIdle()

        givenRecipes(createRecipe("r1", "A", sugar(1.0), flour(3.0), Ingredient(name = "salt")))
        advanceUntilIdle()

        val items = viewModel.list.items!!
        assertEquals(setOf("sugar", "flour", "salt"), items.map { it.key }.toSet())
        assertTrue(items.first { it.key == "sugar" }.isChecked)
        assertTrue(items.first { it.key == "flour" }.sources.single().displayText.startsWith("3"))
    }

    @Test
    fun `a new meal plan for the same recipe adds to the selection only when selected`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0)))
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A")))
        observe(viewModel)
        generate(viewModel)

        mealPlanRepository.saveMealPlan(createEntry("e2", "r1", "A"))
        advanceUntilIdle()

        assertTrue(viewModel.list.items!!.single().sources.single().displayText.startsWith("1"))
    }

    @Test
    fun `saved state restores the list and its check-offs`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0), flour(2.0)))
        val sugarKey = "sugar_r1\u0000sugar\u0000cup"
        val viewModel = createViewModel(
            entries = listOf(createEntry("e1", "r1", "A")),
            savedState = SavedGroceryListState(
                selectedEntryIds = setOf("e1"),
                showingList = true,
                checkedSources = setOf(sugarKey, "no longer on the list")
            )
        )
        observe(viewModel)

        val items = viewModel.list.items!!
        assertTrue(items.first { it.key == "sugar" }.isChecked)
        assertFalse(items.first { it.key == "flour" }.isChecked)
    }

    @Test
    fun `check-offs of deselected meals are kept, not pruned`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0)), createRecipe("r2", "B", flour(1.0)))
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A"), createEntry("e2", "r2", "B")))
        observe(viewModel)
        val flourItem = generate(viewModel).first { it.key == "flour" }
        viewModel.setItemChecked(flourItem, checked = true)

        viewModel.backToSelection()
        viewModel.setEntrySelected("e2", selected = false)
        assertEquals(listOf("sugar"), generate(viewModel).map { it.key })
        assertTrue(groceryListStateStore.states[testWeekStart]!!.checkedSources.containsAll(flourItem.sourceKeys))

        viewModel.backToSelection()
        viewModel.setEntrySelected("e2", selected = true)
        assertTrue(generate(viewModel).first { it.key == "flour" }.isChecked)
    }

    @Test
    fun `checking a source updates its item and is persisted`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0)), createRecipe("r2", "B", sugar(2.0)))
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A"), createEntry("e2", "r2", "B")))
        observe(viewModel)
        val (first, second) = generate(viewModel).single().sources.map { it.key }

        viewModel.setSourceChecked(first, checked = true)
        advanceUntilIdle()
        assertFalse(viewModel.list.items!!.single().isChecked)

        viewModel.setSourceChecked(second, checked = true)
        advanceUntilIdle()
        assertTrue(viewModel.list.items!!.single().isChecked)
        assertEquals(setOf(first, second), groceryListStateStore.states[testWeekStart]!!.checkedSources)
    }

    @Test
    fun `unit settings change the display`() = runTest {
        givenRecipes(createRecipe("r1", "A", sugar(1.0)))
        val viewModel = createViewModel(listOf(createEntry("e1", "r1", "A")))
        observe(viewModel)
        val customary = generate(viewModel).single().sources.single().displayText

        settings.groceryVolumeUnitSystemFlow.value = UnitSystem.METRIC
        advanceUntilIdle()

        val metric = viewModel.list.items!!.single().sources.single().displayText
        assertTrue("Expected a metric amount but got $metric", metric != customary && "mL" in metric)
    }
}
