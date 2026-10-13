package com.lionotter.recipes.ui.screens.recipedetail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import app.cash.turbine.test
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import com.lionotter.recipes.domain.usecase.ExportSingleRecipeUseCase
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.ui.navigation.Route
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric: building a SavedStateHandle from a typed route encodes it with android.net.Uri.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailViewModelTest {

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var recipeRepository: IRecipeRepository
    private lateinit var mealPlanRepository: IMealPlanRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var viewModel: RecipeDetailViewModel
    private val testDispatcher = StandardTestDispatcher()

    private fun createTestRecipe(
        id: String = "recipe-1",
        name: String = "Test Recipe",
        isFavorite: Boolean = false,
        instructionSections: List<InstructionSection> = emptyList()
    ) = Recipe(
        id = id,
        name = name,
        isFavorite = isFavorite,
        instructionSections = instructionSections,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        savedStateHandle = SavedStateHandle(route = Route.RecipeDetail("recipe-1"))
        recipeRepository = mockk()
        mealPlanRepository = mockk()
        settingsDataStore = mockk()

        // Default mock setup
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(createTestRecipe())
        every { settingsDataStore.keepScreenOn } returns flowOf(true)
        every { settingsDataStore.volumeUnitSystem } returns flowOf(UnitSystem.CUSTOMARY)
        every { settingsDataStore.weightUnitSystem } returns flowOf(UnitSystem.METRIC)
        every { settingsDataStore.aiModel } returns flowOf("claude-sonnet-4-5")
        every { settingsDataStore.thinkingEnabled } returns flowOf(true)
        every { settingsDataStore.anthropicApiKey } returns flowOf("test-api-key")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun createViewModel(): RecipeDetailViewModel {
        return RecipeDetailViewModel(
            savedStateHandle = savedStateHandle,
            recipeRepository = recipeRepository,
            mealPlanRepository = mealPlanRepository,
            settingsDataStore = settingsDataStore,
            calculateIngredientUsage = CalculateIngredientUsageUseCase(),
            exportSingleRecipeUseCase = mockk<ExportSingleRecipeUseCase>(),
            recipeSerializer = mockk<RecipeSerializer>(),
            applicationContext = mockk(relaxed = true)
        )
    }

    @Test
    fun `initial scale is 1`() = runTest {
        viewModel = createViewModel()

        viewModel.scale.test {
            assertEquals(1.0, awaitItem(), 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setScale updates scale value`() = runTest {
        viewModel = createViewModel()

        viewModel.scale.test {
            assertEquals(1.0, awaitItem(), 0.01)

            viewModel.setScale(2.0)
            assertEquals(2.0, awaitItem(), 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setScale clamps to minimum 0 25`() = runTest {
        viewModel = createViewModel()

        viewModel.scale.test {
            awaitItem() // Skip initial

            viewModel.setScale(0.1)
            assertEquals(0.25, awaitItem(), 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setScale clamps to maximum 10`() = runTest {
        viewModel = createViewModel()

        viewModel.scale.test {
            awaitItem() // Skip initial

            viewModel.setScale(15.0)
            assertEquals(10.0, awaitItem(), 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `incrementScale increases scale by 0 5`() = runTest {
        viewModel = createViewModel()

        viewModel.scale.test {
            assertEquals(1.0, awaitItem(), 0.01)

            viewModel.incrementScale()
            assertEquals(1.5, awaitItem(), 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `decrementScale decreases scale by 0 5`() = runTest {
        viewModel = createViewModel()

        viewModel.scale.test {
            assertEquals(1.0, awaitItem(), 0.01)

            viewModel.decrementScale()
            assertEquals(0.5, awaitItem(), 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial measurementPreference is DEFAULT`() = runTest {
        viewModel = createViewModel()

        viewModel.measurementPreference.test {
            assertEquals(MeasurementPreference.DEFAULT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setMeasurementPreference updates preference`() = runTest {
        viewModel = createViewModel()

        viewModel.measurementPreference.test {
            assertEquals(MeasurementPreference.DEFAULT, awaitItem())

            viewModel.setMeasurementPreference(MeasurementPreference.WEIGHT)
            assertEquals(MeasurementPreference.WEIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleInstructionIngredientUsed adds ingredient to used set`() = runTest {
        viewModel = createViewModel()

        viewModel.usedInstructionIngredients.test {
            assertTrue(awaitItem().isEmpty())

            viewModel.toggleInstructionIngredientUsed(0, 0, 0)
            val used = awaitItem()
            assertTrue(used.contains("0-0-0"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleInstructionIngredientUsed removes ingredient when already used`() = runTest {
        viewModel = createViewModel()

        viewModel.usedInstructionIngredients.test {
            assertTrue(awaitItem().isEmpty())

            // Add
            viewModel.toggleInstructionIngredientUsed(0, 1, 2)
            assertTrue(awaitItem().contains("0-1-2"))

            // Remove
            viewModel.toggleInstructionIngredientUsed(0, 1, 2)
            assertFalse(awaitItem().contains("0-1-2"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isInstructionIngredientUsed returns correct status`() = runTest {
        viewModel = createViewModel()

        assertFalse(viewModel.isInstructionIngredientUsed(0, 0, 0))

        viewModel.toggleInstructionIngredientUsed(0, 0, 0)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.isInstructionIngredientUsed(0, 0, 0))
        assertFalse(viewModel.isInstructionIngredientUsed(0, 0, 1))
    }

    @Test
    fun `resetIngredientUsage clears all used ingredients`() = runTest {
        viewModel = createViewModel()

        viewModel.usedInstructionIngredients.test {
            assertTrue(awaitItem().isEmpty())

            viewModel.toggleInstructionIngredientUsed(0, 0, 0)
            viewModel.toggleInstructionIngredientUsed(0, 1, 0)
            awaitItem()
            val used = awaitItem()
            assertEquals(2, used.size)

            viewModel.resetIngredientUsage()
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleHighlightedInstructionStep sets highlighted step`() = runTest {
        viewModel = createViewModel()

        viewModel.highlightedInstructionStep.test {
            assertNull(awaitItem())

            viewModel.toggleHighlightedInstructionStep(1, 2)
            val highlighted = awaitItem()
            assertEquals(1, highlighted?.sectionIndex)
            assertEquals(2, highlighted?.stepIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleHighlightedInstructionStep clears when same step toggled`() = runTest {
        viewModel = createViewModel()

        viewModel.highlightedInstructionStep.test {
            assertNull(awaitItem())

            viewModel.toggleHighlightedInstructionStep(1, 2)
            assertEquals(HighlightedInstructionStep(1, 2), awaitItem())

            viewModel.toggleHighlightedInstructionStep(1, 2)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleFavorite calls repository with toggled value`() = runTest {
        val recipe = createTestRecipe(isFavorite = false)
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(recipe)
        every { recipeRepository.setFavorite("recipe-1", true) } just runs

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavorite()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { recipeRepository.setFavorite("recipe-1", true) }
    }

    @Test
    fun `toggleFavorite toggles from true to false`() = runTest {
        val recipe = createTestRecipe(isFavorite = true)
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(recipe)
        every { recipeRepository.setFavorite("recipe-1", false) } just runs

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavorite()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { recipeRepository.setFavorite("recipe-1", false) }
    }

    @Test
    fun `deleteRecipe calls repository and emits event`() = runTest {
        coEvery { mealPlanRepository.deleteMealPlansByRecipeId("recipe-1") } just runs
        every { recipeRepository.deleteRecipe("recipe-1") } just runs

        viewModel = createViewModel()

        viewModel.recipeDeleted.test {
            viewModel.deleteRecipe()
            testDispatcher.scheduler.advanceUntilIdle()

            awaitItem() // Unit event
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { mealPlanRepository.deleteMealPlansByRecipeId("recipe-1") }
        verify { recipeRepository.deleteRecipe("recipe-1") }
    }

    @Test
    fun `supportsConversion is false for empty recipe`() = runTest {
        val recipe = createTestRecipe(instructionSections = emptyList())
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(recipe)

        viewModel = createViewModel()

        viewModel.supportsConversion.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `supportsConversion is true when ingredient has density`() = runTest {
        val recipe = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(
                    steps = listOf(
                        InstructionStep(
                            stepNumber = 1,
                            instruction = "Mix",
                            ingredients = listOf(
                                Ingredient(
                                    name = "flour",
                                    amount = Amount(value = 2.0, unit = "cup"),
                                    density = 0.51
                                )
                            )
                        )
                    )
                )
            )
        )
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(recipe)

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.supportsConversion.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `createInstructionIngredientKey creates correct format`() {
        val key = createInstructionIngredientKey(1, 2, 3)
        assertEquals("1-2-3", key)
    }

    @Test
    fun `HighlightedInstructionStep holds correct values`() {
        val step = HighlightedInstructionStep(sectionIndex = 1, stepIndex = 2)
        assertEquals(1, step.sectionIndex)
        assertEquals(2, step.stepIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws exception when recipeId not in savedStateHandle`() {
        savedStateHandle = SavedStateHandle(emptyMap())
        createViewModel()
    }

    @Test
    fun `cooking progress is restored from SavedStateHandle`() = runTest {
        viewModel = createViewModel()
        viewModel.setScale(2.0)
        viewModel.setMeasurementPreference(MeasurementPreference.WEIGHT)
        viewModel.toggleInstructionIngredientUsed(0, 1, 2)
        viewModel.toggleHighlightedInstructionStep(0, 3)
        viewModel.toggleAggregateIngredientChecked("|flour")

        // Simulate process death: a new ViewModel gets the same saved state
        val restored = createViewModel()

        assertEquals(2.0, restored.scale.value, 0.001)
        assertEquals(MeasurementPreference.WEIGHT, restored.measurementPreference.value)
        assertEquals(setOf("0-1-2"), restored.usedInstructionIngredients.value)
        assertEquals(HighlightedInstructionStep(0, 3), restored.highlightedInstructionStep.value)
        assertEquals(setOf("|flour"), restored.checkedAggregateIngredients.value)
    }

    @Test
    fun `resetCookingProgress clears checked ingredients and highlighted step but keeps scale`() = runTest {
        viewModel = createViewModel()
        viewModel.setScale(2.0)
        viewModel.toggleInstructionIngredientUsed(0, 0, 0)
        viewModel.toggleHighlightedInstructionStep(0, 0)
        viewModel.toggleAggregateIngredientChecked("|flour")

        viewModel.resetCookingProgress()

        assertTrue(viewModel.usedInstructionIngredients.value.isEmpty())
        assertNull(viewModel.highlightedInstructionStep.value)
        assertTrue(viewModel.checkedAggregateIngredients.value.isEmpty())
        assertEquals(2.0, viewModel.scale.value, 0.001)

        val restored = createViewModel()
        assertTrue(restored.usedInstructionIngredients.value.isEmpty())
        assertNull(restored.highlightedInstructionStep.value)
    }

    @Test
    fun `uiState is NotFound when recipe does not exist`() = runTest {
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(null)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RecipeDetailState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun `uiState is Loaded when recipe exists`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RecipeDetailState.Loaded(createTestRecipe()), viewModel.uiState.value)
    }

    @Test
    fun `deleteRecipe failure reports error instead of navigating away`() = runTest {
        coEvery { mealPlanRepository.deleteMealPlansByRecipeId("recipe-1") } just runs
        every { recipeRepository.deleteRecipe("recipe-1") } throws RuntimeException("offline")

        viewModel = createViewModel()

        viewModel.errors.test {
            viewModel.deleteRecipe()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(RecipeDetailError.DELETE_FAILED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.recipeDeleted.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `incrementScale adds one serving when servings are known`() = runTest {
        every { recipeRepository.getRecipeById("recipe-1") } returns
            flowOf(createTestRecipe().copy(servings = 4))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.incrementScale()
        assertEquals(5.0 / 4, viewModel.scale.value, 0.001)

        viewModel.decrementScale()
        viewModel.decrementScale()
        assertEquals(3.0 / 4, viewModel.scale.value, 0.001)
    }

    @Test
    fun `decrementScale does not go below one serving`() = runTest {
        every { recipeRepository.getRecipeById("recipe-1") } returns
            flowOf(createTestRecipe().copy(servings = 2))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        repeat(5) { viewModel.decrementScale() }
        assertEquals(0.5, viewModel.scale.value, 0.001)
    }

    @Test
    fun `scale steps through presets when servings are unknown`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.incrementScale()
        viewModel.incrementScale()
        assertEquals(2.0, viewModel.scale.value, 0.001)
        repeat(10) { viewModel.decrementScale() }
        assertEquals(0.25, viewModel.scale.value, 0.001)
    }

    @Test
    fun `notes are saved after typing stops`() = runTest {
        every { recipeRepository.setUserNotes(any(), any()) } just runs
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNotesChange("Less salt")
        testDispatcher.scheduler.advanceTimeBy(RecipeDetailViewModel.NOTES_SAVE_DEBOUNCE_MS / 2)
        verify(exactly = 0) { recipeRepository.setUserNotes(any(), any()) }

        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { recipeRepository.setUserNotes("recipe-1", "Less salt") }
        assertEquals("Less salt", viewModel.notesDraft)
    }

    @Test
    fun `flushNotes saves pending notes immediately`() = runTest {
        every { recipeRepository.setUserNotes(any(), any()) } just runs
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNotesChange("Double the garlic")
        viewModel.flushNotes()

        verify(exactly = 1) { recipeRepository.setUserNotes("recipe-1", "Double the garlic") }
    }

    @Test
    fun `notes draft survives recreation`() = runTest {
        every { recipeRepository.setUserNotes(any(), any()) } just runs
        viewModel = createViewModel()
        viewModel.onNotesChange("Half-typed")

        val restored = createViewModel()
        assertEquals("Half-typed", restored.notesDraft)
    }
}
