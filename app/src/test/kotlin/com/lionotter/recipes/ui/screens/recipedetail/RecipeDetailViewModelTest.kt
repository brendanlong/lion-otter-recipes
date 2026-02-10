package com.lionotter.recipes.ui.screens.recipedetail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailViewModelTest {

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var recipeRepository: RecipeRepository
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
        savedStateHandle = SavedStateHandle(mapOf("recipeId" to "recipe-1"))
        recipeRepository = mockk()
        settingsDataStore = mockk()

        // Default mock setup
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(createTestRecipe())
        every { settingsDataStore.keepScreenOn } returns flowOf(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): RecipeDetailViewModel {
        return RecipeDetailViewModel(
            savedStateHandle = savedStateHandle,
            recipeRepository = recipeRepository,
            settingsDataStore = settingsDataStore,
            calculateIngredientUsage = CalculateIngredientUsageUseCase()
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
        coEvery { recipeRepository.setFavorite("recipe-1", true) } just runs

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavorite()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { recipeRepository.setFavorite("recipe-1", true) }
    }

    @Test
    fun `toggleFavorite toggles from true to false`() = runTest {
        val recipe = createTestRecipe(isFavorite = true)
        every { recipeRepository.getRecipeById("recipe-1") } returns flowOf(recipe)
        coEvery { recipeRepository.setFavorite("recipe-1", false) } just runs

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavorite()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { recipeRepository.setFavorite("recipe-1", false) }
    }

    @Test
    fun `deleteRecipe calls repository and emits event`() = runTest {
        coEvery { recipeRepository.deleteRecipe("recipe-1") } just runs

        viewModel = createViewModel()

        viewModel.recipeDeleted.test {
            viewModel.deleteRecipe()
            testDispatcher.scheduler.advanceUntilIdle()

            awaitItem() // Unit event
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { recipeRepository.deleteRecipe("recipe-1") }
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
}
