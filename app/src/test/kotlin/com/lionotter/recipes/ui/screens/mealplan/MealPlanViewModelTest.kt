package com.lionotter.recipes.ui.screens.mealplan

import app.cash.turbine.test
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var mealPlanRepository: IMealPlanRepository
    private lateinit var recipeRepository: IRecipeRepository
    private lateinit var settingsDataStore: SettingsDataStore

    private val recipe = Recipe(
        id = "r1",
        name = "Pasta",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mealPlanRepository = mockk()
        recipeRepository = mockk()
        settingsDataStore = mockk()
        every { settingsDataStore.startOfWeek } returns flowOf(StartOfWeek.MONDAY)
        every { recipeRepository.getAllRecipes() } returns flowOf(listOf(recipe))
        every { mealPlanRepository.getMealPlansForDateRange(any(), any()) } returns flowOf(emptyList())
        every { mealPlanRepository.saveMealPlan(any()) } just runs
        every { mealPlanRepository.deleteMealPlan(any()) } just runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = MealPlanViewModel(
        mealPlanRepository = mealPlanRepository,
        recipeRepository = recipeRepository,
        getTagsUseCase = GetTagsUseCase(),
        settingsDataStore = settingsDataStore
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
        val countGate = CompletableDeferred<Int>()
        coEvery { mealPlanRepository.getMealPlanCount() } coAnswers { countGate.await() }
        val viewModel = createViewModel()
        viewModel.openAddDialog()

        viewModel.addRecipeToMealPlan(recipe)
        viewModel.addRecipeToMealPlan(recipe)
        advanceUntilIdle()
        countGate.complete(0)
        advanceUntilIdle()

        verify(exactly = 1) { mealPlanRepository.saveMealPlan(any()) }
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

        viewModel.deleteMealPlan(entry)
        advanceUntilIdle()
        assertEquals(entry, viewModel.recentlyDeleted.value)

        val saved = slot<MealPlanEntry>()
        every { mealPlanRepository.saveMealPlan(capture(saved)) } just runs
        viewModel.undoDelete()
        advanceUntilIdle()

        verify { mealPlanRepository.deleteMealPlan("e1") }
        assertNull(viewModel.recentlyDeleted.value)
        assertEquals(entry.copy(id = saved.captured.id), saved.captured)
        assertNotEquals("e1", saved.captured.id)
        coVerify(exactly = 0) { mealPlanRepository.getMealPlanCount() }
    }
}
