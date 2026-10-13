package com.lionotter.recipes.ui.screens.mealplan

import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.testutil.FakeAppSettings
import com.lionotter.recipes.testutil.FakeCurrentDate
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MealPlanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mealPlanRepository = FakeMealPlanRepository()
    private val settings = FakeAppSettings().apply { startOfWeekFlow.value = StartOfWeek.MONDAY }

    // A Wednesday; its week starts Monday 2025-01-13
    private val today = LocalDate(2025, 1, 15)
    private val currentDate = FakeCurrentDate(today)

    private val recipe = Recipe(
        id = "r1",
        name = "Pasta",
        tags = listOf("dinner"),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )
    private val otherRecipe = recipe.copy(id = "r2", name = "Cake", tags = listOf("dessert"))
    private var recipeRepository: RecipeRepository = FakeRecipeRepository(recipe, otherRecipe)

    private val entry = MealPlanEntry(
        id = "e1",
        recipeId = "r1",
        recipeName = "Pasta",
        recipeImageUrl = null,
        date = today,
        mealType = MealType.DINNER,
        servings = 2.0,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = MealPlanViewModel(
        mealPlanRepository = mealPlanRepository,
        recipeRepository = recipeRepository,
        getTagsUseCase = GetTagsUseCase(),
        settings = settings,
        currentDate = currentDate,
        defaultDispatcher = testDispatcher
    )

    /** Subscribes to the screen's state, as the screen does. */
    private fun TestScope.observe(viewModel: MealPlanViewModel) {
        backgroundScope.launch { viewModel.currentWeekStart.collect {} }
        backgroundScope.launch { viewModel.weekMealPlans.collect {} }
        backgroundScope.launch { viewModel.today.collect {} }
        backgroundScope.launch { viewModel.recipeChoices.collect {} }
        advanceUntilIdle()
    }

    @Test
    fun `new meal defaults to today when today is in the viewed week`() = runTest {
        val viewModel = createViewModel()
        observe(viewModel)

        viewModel.openAddDialog()

        assertEquals(MealDialog.Add(date = today), viewModel.dialog.value)
    }

    @Test
    fun `new meal defaults to week start when viewing another week`() = runTest {
        val viewModel = createViewModel()
        observe(viewModel)
        viewModel.navigateWeek(forward = true)
        advanceUntilIdle()

        viewModel.openAddDialog()

        assertEquals(LocalDate(2025, 1, 20), viewModel.dialog.value?.date)
    }

    @Test
    fun `reopening the dialog starts from fresh fields`() = runTest {
        val viewModel = createViewModel()
        observe(viewModel)
        viewModel.openEditDialog(entry)
        viewModel.setServings(3.5)
        viewModel.setMealType(MealType.LUNCH)
        viewModel.onRecipeSearchQueryChange("cake")
        viewModel.onTagFilterSelected("dessert")
        viewModel.dismissDialog()
        assertNull(viewModel.dialog.value)

        viewModel.openAddDialog()

        assertEquals(MealDialog.Add(date = today), viewModel.dialog.value)
    }

    @Test
    fun `edit dialog starts from the entry and saves its fields`() = runTest {
        mealPlanRepository.saveMealPlan(entry)
        val viewModel = createViewModel()
        observe(viewModel)

        viewModel.openEditDialog(entry)
        assertEquals(MealDialog.Edit(entry, date = today, mealType = MealType.DINNER, servings = 2.0), viewModel.dialog.value)
        viewModel.setDate(today.plusDays(1))
        viewModel.setMealType(MealType.LUNCH)
        viewModel.setServings(0.5)
        viewModel.saveEditedMealPlan()
        advanceUntilIdle()

        val saved = mealPlanRepository.entries.value.getValue("e1")
        assertEquals(Triple(today.plusDays(1), MealType.LUNCH, 0.5), Triple(saved.date, saved.mealType, saved.servings))
        assertNull(viewModel.dialog.value)
    }

    @Test
    fun `picking a recipe while editing swaps the meal's recipe`() = runTest {
        mealPlanRepository.saveMealPlan(entry)
        val viewModel = createViewModel()
        observe(viewModel)

        viewModel.openEditDialog(entry)
        viewModel.addRecipeToMealPlan(otherRecipe)
        advanceUntilIdle()

        val saved = mealPlanRepository.entries.value.values.single()
        assertEquals("e1" to "r2", saved.id to saved.recipeId)
        assertEquals(2.0, saved.servings, 0.0)
    }

    @Test
    fun `the dialog's search and tag filter the recipes`() = runTest {
        val viewModel = createViewModel()
        observe(viewModel)
        viewModel.openAddDialog()
        advanceUntilIdle()
        assertEquals(listOf("Cake", "Pasta"), viewModel.recipeChoices.value.recipes.map { it.name })
        assertEquals(setOf("dinner", "dessert"), viewModel.recipeChoices.value.availableTags.toSet())

        viewModel.onTagFilterSelected("dessert")
        advanceUntilIdle()
        assertEquals(listOf("Cake"), viewModel.recipeChoices.value.recipes.map { it.name })

        viewModel.onTagFilterSelected("dessert")
        viewModel.onRecipeSearchQueryChange("past")
        advanceUntilIdle()
        assertEquals(listOf("Pasta"), viewModel.recipeChoices.value.recipes.map { it.name })
    }

    @Test
    fun `double tapping a recipe only adds one meal`() = runTest {
        val countGate = CompletableDeferred<Unit>()
        mealPlanRepository.beforeCount = { countGate.await() }
        val viewModel = createViewModel()
        viewModel.openAddDialog()

        viewModel.addRecipeToMealPlan(recipe)
        viewModel.addRecipeToMealPlan(recipe)
        advanceUntilIdle()
        assertTrue(viewModel.dialog.value!!.isSaving)
        countGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, mealPlanRepository.entries.value.size)
        assertNull(viewModel.dialog.value)
    }

    @Test
    fun `a dialog reopened during a save stays open when the save finishes`() = runTest {
        val countGate = CompletableDeferred<Unit>()
        mealPlanRepository.beforeCount = { countGate.await() }
        val viewModel = createViewModel()
        viewModel.openAddDialog()
        viewModel.addRecipeToMealPlan(recipe)
        advanceUntilIdle()

        viewModel.dismissDialog()
        viewModel.openAddDialog()
        countGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MealDialog.Add(date = today), viewModel.dialog.value)
    }

    @Test
    fun `the meal plan limit keeps the dialog open and says why`() = runTest {
        repeat(ResourceLimits.MAX_MEAL_PLANS) { i ->
            mealPlanRepository.saveMealPlan(entry.copy(id = "e$i", date = LocalDate(2020, 1, 1)))
        }
        val viewModel = createViewModel()
        viewModel.openAddDialog()

        viewModel.addRecipeToMealPlan(recipe)
        advanceUntilIdle()

        assertFalse(viewModel.dialog.value!!.isSaving)
        assertTrue(viewModel.events.first() is MealPlanEvent.ShowMessage)
    }

    @Test
    fun `a week that fails to load is shown as failed`() = runTest {
        mealPlanRepository.loadError = IllegalStateException("boom")
        val viewModel = createViewModel()
        observe(viewModel)

        assertEquals(WeekMealPlans.Failed, viewModel.weekMealPlans.value)
    }

    @Test
    fun `recipes that fail to load are shown as failed`() = runTest {
        recipeRepository = FailingRecipeRepository()
        val viewModel = createViewModel()
        observe(viewModel)

        assertTrue(viewModel.recipeChoices.value.loadFailed)
    }

    @Test
    fun `the week follows today across midnight unless the user navigated away`() = runTest {
        val viewModel = createViewModel()
        observe(viewModel)
        assertEquals(LocalDate(2025, 1, 13), viewModel.currentWeekStart.value)

        currentDate.date.value = LocalDate(2025, 1, 20)
        advanceUntilIdle()
        assertEquals(LocalDate(2025, 1, 20), viewModel.today.value)
        assertEquals(LocalDate(2025, 1, 20), viewModel.currentWeekStart.value)

        viewModel.navigateWeek(forward = false)
        currentDate.date.value = LocalDate(2025, 1, 27)
        advanceUntilIdle()
        assertEquals(LocalDate(2025, 1, 13), viewModel.currentWeekStart.value)

        viewModel.goToToday()
        advanceUntilIdle()
        assertEquals(LocalDate(2025, 1, 27), viewModel.currentWeekStart.value)
    }

    @Test
    fun `undo restores a deleted meal`() = runTest {
        val viewModel = createViewModel()

        mealPlanRepository.saveMealPlan(entry)
        mealPlanRepository.countCalls = 0

        viewModel.deleteMealPlan(entry)
        advanceUntilIdle()
        assertEquals(MealPlanEvent.MealRemoved(entry), viewModel.events.first())
        assertEquals(emptyMap<String, MealPlanEntry>(), mealPlanRepository.entries.value)

        viewModel.undoDelete(entry)
        advanceUntilIdle()

        val restored = mealPlanRepository.entries.value.values.single()
        assertEquals(entry.copy(id = restored.id), restored)
        assertNotEquals("e1", restored.id)
        assertEquals(0, mealPlanRepository.countCalls)
    }

    private class FailingRecipeRepository : RecipeRepository by FakeRecipeRepository() {
        override fun getAllRecipes(): Flow<List<Recipe>> = flow { throw IllegalStateException("boom") }
    }

    private fun LocalDate.plusDays(days: Int) = LocalDate.fromEpochDays(toEpochDays() + days)
}
