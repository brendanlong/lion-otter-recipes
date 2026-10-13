package com.lionotter.recipes.ui.screens.mealplan

import app.cash.turbine.test
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.testutil.FakeAppSettings
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MealPlanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mealPlanRepository = FakeMealPlanRepository()
    private val settings = FakeAppSettings().apply { startOfWeekFlow.value = StartOfWeek.MONDAY }

    private val recipe = Recipe(
        id = "r1",
        name = "Pasta",
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
        recipeRepository = FakeRecipeRepository(recipe),
        getTagsUseCase = GetTagsUseCase(),
        settings = settings,
        defaultDispatcher = testDispatcher
    )

    private val today: LocalDate get() = Clock.System.todayIn(TimeZone.currentSystemDefault())

    @Test
    fun `new meal defaults to today when today is in the viewed week`() = runTest {
        val viewModel = createViewModel()
        viewModel.currentWeekStart.test {
            advanceUntilIdle()
            expectMostRecentItem()
            viewModel.openAddDialog()
            assertEquals(today, viewModel.selectedDate.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `new meal defaults to week start when viewing another week`() = runTest {
        val viewModel = createViewModel()
        viewModel.currentWeekStart.test {
            advanceUntilIdle()
            expectMostRecentItem()
            viewModel.navigateWeek(forward = true)
            advanceUntilIdle()
            val nextWeekStart = expectMostRecentItem()
            viewModel.openAddDialog()
            assertEquals(nextWeekStart, viewModel.selectedDate.value)
            cancelAndIgnoreRemainingEvents()
        }
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
        countGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, mealPlanRepository.entries.value.size)
        assertFalse(viewModel.showAddDialog.value)
        assertFalse(viewModel.isSaving.value)
    }

    @Test
    fun `undo restores a deleted meal`() = runTest {
        val entry = MealPlanEntry(
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
}
