package com.lionotter.recipes.ui.screens.recipedetail

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import app.cash.turbine.test
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import com.lionotter.recipes.domain.usecase.DeleteRecipeUseCase
import com.lionotter.recipes.domain.usecase.ExportSingleRecipeUseCase
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.testutil.FakeAppSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Instant

// Robolectric: building a SavedStateHandle from a typed route encodes it with android.net.Uri.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailViewModelTest {

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var recipeRepository: FakeRecipeRepository
    private val mealPlanRepository = FakeMealPlanRepository()
    private val settings = FakeAppSettings(apiKey = "test-api-key").apply {
        volumeUnitSystemFlow.value = UnitSystem.CUSTOMARY
        weightUnitSystemFlow.value = UnitSystem.METRIC
    }
    private lateinit var viewModel: RecipeDetailViewModel
    private val testDispatcher = StandardTestDispatcher()
    private val recipeSerializer = RecipeSerializer(AppJson)

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
        savedStateHandle = SavedStateHandle(route = Route.RecipeDetail("recipe-1"))
        recipeRepository = FakeRecipeRepository(createTestRecipe())
    }

    private fun givenRecipe(recipe: Recipe) {
        recipeRepository.recipes.value = mapOf(recipe.id to recipe)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The progress once the ViewModel has caught up with the saved state. */
    private val RecipeDetailViewModel.progress: CookingProgress
        get() {
            testDispatcher.scheduler.advanceUntilIdle()
            return cookingProgress.value
        }

    /** A handle holding only what survives process death: the saved values. */
    private fun SavedStateHandle.afterProcessDeath(): SavedStateHandle {
        val bundle = Bundle()
        keys().forEach { key -> bundle.putString(key, get<Any?>(key)?.toString()) }
        return SavedStateHandle(keys().associateWith { bundle.getString(it) })
    }

    private fun createViewModel(): RecipeDetailViewModel {
        return RecipeDetailViewModel(
            savedStateHandle = savedStateHandle,
            recipeRepository = recipeRepository,
            mealPlanRepository = mealPlanRepository,
            deleteRecipeUseCase = DeleteRecipeUseCase(recipeRepository, mealPlanRepository, FakeImageStore()),
            settings = settings,
            calculateIngredientUsage = CalculateIngredientUsageUseCase(),
            exportSingleRecipeUseCase = ExportSingleRecipeUseCase(recipeSerializer, FakeImageStore()),
            recipeSerializer = recipeSerializer,
            applicationContext = RuntimeEnvironment.getApplication(),
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `cooking progress starts empty at scale 1`() = runTest {
        viewModel = createViewModel()

        assertEquals(CookingProgress(), viewModel.progress)
        assertFalse(viewModel.progress.hasProgress)
    }

    @Test
    fun `setScale clamps to 0_25 to 10`() = runTest {
        viewModel = createViewModel()

        viewModel.setScale(2.5)
        assertEquals(2.5, viewModel.progress.scale, 0.001)
        viewModel.setScale(0.1)
        assertEquals(0.25, viewModel.progress.scale, 0.001)
        viewModel.setScale(15.0)
        assertEquals(10.0, viewModel.progress.scale, 0.001)
    }

    @Test
    fun `setMeasurementPreference updates preference`() = runTest {
        viewModel = createViewModel()

        viewModel.setMeasurementPreference(MeasurementPreference.WEIGHT)

        assertEquals(MeasurementPreference.WEIGHT, viewModel.progress.measurementPreference)
    }

    @Test
    fun `toggling an instruction ingredient marks and unmarks it`() = runTest {
        viewModel = createViewModel()

        viewModel.toggleInstructionIngredientUsed(0, 0, 0)
        viewModel.toggleInstructionIngredientUsed(0, 0, 1)
        assertEquals(setOf("0-0-0", "0-0-1"), viewModel.progress.usedInstructionIngredients)
        assertTrue(viewModel.progress.hasProgress)

        viewModel.toggleInstructionIngredientUsed(0, 0, 0)
        assertEquals(setOf("0-0-1"), viewModel.progress.usedInstructionIngredients)
    }

    @Test
    fun `rapid toggles are not lost`() = runTest {
        viewModel = createViewModel()

        // No dispatching in between, as with taps faster than the state flow updates
        repeat(3) { viewModel.toggleInstructionIngredientUsed(0, 0, it) }
        viewModel.toggleAggregateIngredientChecked("|flour")
        viewModel.incrementScale()

        val progress = viewModel.progress
        assertEquals(setOf("0-0-0", "0-0-1", "0-0-2"), progress.usedInstructionIngredients)
        assertEquals(setOf("|flour"), progress.checkedAggregateIngredients)
        assertEquals(1.5, progress.scale, 0.001)
    }

    @Test
    fun `resetIngredientUsage clears all used ingredients`() = runTest {
        viewModel = createViewModel()
        viewModel.toggleInstructionIngredientUsed(0, 0, 0)

        viewModel.resetIngredientUsage()

        assertTrue(viewModel.progress.usedInstructionIngredients.isEmpty())
    }

    @Test
    fun `toggling the highlighted step sets and clears it`() = runTest {
        viewModel = createViewModel()

        viewModel.toggleHighlightedInstructionStep(1, 2)
        assertEquals(HighlightedInstructionStep(1, 2), viewModel.progress.highlightedStep)

        viewModel.toggleHighlightedInstructionStep(1, 2)
        assertNull(viewModel.progress.highlightedStep)
    }

    @Test
    fun `ingredient usage follows the used ingredients and the recipe`() = runTest {
        val flour = Ingredient(name = "flour", amount = Amount(value = 2.0, unit = "cup"))
        fun recipeWith(vararg ingredients: Ingredient) = createTestRecipe(
            instructionSections = listOf(
                InstructionSection(steps = listOf(InstructionStep(stepNumber = 1, instruction = "Mix", ingredients = ingredients.toList())))
            )
        )
        givenRecipe(recipeWith(flour))
        viewModel = createViewModel()
        backgroundScope.launch { viewModel.ingredientUsageBySection.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.ingredientUsageBySection.value.getValue(null).getValue("flour").isFullyUsed)

        viewModel.toggleInstructionIngredientUsed(0, 0, 0)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.ingredientUsageBySection.value.getValue(null).getValue("flour").isFullyUsed)

        givenRecipe(recipeWith(flour, Ingredient(name = "sugar")))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("sugar" in viewModel.ingredientUsageBySection.value.getValue(null))
    }

    @Test
    fun `setFavorite writes the value the user asked for`() = runTest {
        givenRecipe(createTestRecipe(isFavorite = true))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setFavorite(false)
        viewModel.setFavorite(false)

        assertEquals(listOf("recipe-1" to false, "recipe-1" to false), recipeRepository.favoriteUpdates)
    }

    @Test
    fun `deleteRecipe calls repository and emits event`() = runTest {

        viewModel = createViewModel()

        viewModel.events.test {
            viewModel.deleteRecipe()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(RecipeDetailEvent.Deleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("recipe-1"), mealPlanRepository.deletedForRecipes)
        assertEquals(listOf("recipe-1"), recipeRepository.deleted)
    }

    @Test
    fun `supportsConversion is false for empty recipe`() = runTest {
        val recipe = createTestRecipe(instructionSections = emptyList())
        givenRecipe(recipe)

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
        givenRecipe(recipe)

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

        savedStateHandle = savedStateHandle.afterProcessDeath()
        val restored = createViewModel()

        assertEquals(
            CookingProgress(
                scale = 2.0,
                measurementPreference = MeasurementPreference.WEIGHT,
                usedInstructionIngredients = setOf("0-1-2"),
                highlightedStep = HighlightedInstructionStep(0, 3),
                checkedAggregateIngredients = setOf("|flour")
            ),
            restored.progress
        )
    }

    @Test
    fun `resetCookingProgress clears checked ingredients and highlighted step but keeps scale`() = runTest {
        viewModel = createViewModel()
        viewModel.setScale(2.0)
        viewModel.toggleInstructionIngredientUsed(0, 0, 0)
        viewModel.toggleHighlightedInstructionStep(0, 0)
        viewModel.toggleAggregateIngredientChecked("|flour")

        viewModel.resetCookingProgress()

        assertEquals(CookingProgress(scale = 2.0), viewModel.progress)

        savedStateHandle = savedStateHandle.afterProcessDeath()
        assertEquals(CookingProgress(scale = 2.0), createViewModel().progress)
    }

    @Test
    fun `uiState is NotFound when recipe does not exist`() = runTest {
        recipeRepository.recipes.value = emptyMap()
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
        mealPlanRepository.deleteByRecipeError = IllegalStateException("offline")

        viewModel = createViewModel()

        viewModel.events.test {
            viewModel.deleteRecipe()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(RecipeDetailEvent.ShowMessage(UserMessage(R.string.delete_recipe_failed)), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<String>(), recipeRepository.deleted)
    }

    @Test
    fun `incrementScale adds one serving when servings are known`() = runTest {
        givenRecipe(createTestRecipe().copy(servings = 4))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.incrementScale()
        assertEquals(5.0 / 4, viewModel.progress.scale, 0.001)

        viewModel.decrementScale()
        viewModel.decrementScale()
        assertEquals(3.0 / 4, viewModel.progress.scale, 0.001)
    }

    @Test
    fun `decrementScale does not go below one serving`() = runTest {
        givenRecipe(createTestRecipe().copy(servings = 2))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        repeat(5) { viewModel.decrementScale() }
        assertEquals(0.5, viewModel.progress.scale, 0.001)
    }

    @Test
    fun `scale steps through presets when servings are unknown`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.incrementScale()
        viewModel.incrementScale()
        assertEquals(2.0, viewModel.progress.scale, 0.001)
        repeat(10) { viewModel.decrementScale() }
        assertEquals(0.25, viewModel.progress.scale, 0.001)
    }

    @Test
    fun `notes are saved after typing stops`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNotesChange("Less salt")
        testDispatcher.scheduler.advanceTimeBy(RecipeDetailViewModel.NOTES_SAVE_DEBOUNCE_MS / 2)
        assertEquals(emptyList<Any>(), recipeRepository.notesUpdates)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("recipe-1" to "Less salt"), recipeRepository.notesUpdates)
    }

    @Test
    fun `the draft is dropped once the recipe shows it, so later remote edits show`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNotesChange("Less salt")
        assertEquals("Less salt", viewModel.notesDraft.value)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.notesDraft.value)

        recipeRepository.setUserNotes("recipe-1", "Edited on another device")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.notesDraft.value)
        assertEquals("Edited on another device", (viewModel.uiState.value as RecipeDetailState.Loaded).recipe.userNotes)
    }

    @Test
    fun `typing only whitespace into empty notes is kept`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNotesChange(" ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(" ", viewModel.notesDraft.value)
        assertEquals(emptyList<Any>(), recipeRepository.notesUpdates)
    }

    @Test
    fun `an unsaved draft is kept while the recipe shows other notes`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNotesChange("Typing")
        recipeRepository.setUserNotes("recipe-1", "Remote")
        testDispatcher.scheduler.advanceTimeBy(RecipeDetailViewModel.NOTES_SAVE_DEBOUNCE_MS / 2)

        assertEquals("Typing", viewModel.notesDraft.value)
    }

    @Test
    fun `flushNotes saves pending notes immediately`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNotesChange("Double the garlic")
        viewModel.flushNotes()

        assertEquals(listOf("recipe-1" to "Double the garlic"), recipeRepository.notesUpdates)
    }

    @Test
    fun `notes draft survives recreation`() = runTest {
        viewModel = createViewModel()
        viewModel.onNotesChange("Half-typed")

        savedStateHandle = savedStateHandle.afterProcessDeath()
        val restored = createViewModel()
        assertEquals("Half-typed", restored.notesDraft.value)
    }
}
