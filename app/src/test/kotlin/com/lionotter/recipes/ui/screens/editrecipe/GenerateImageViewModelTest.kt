package com.lionotter.recipes.ui.screens.editrecipe

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.AiFailure
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.WriteImagePromptUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.testutil.FakeAiSettings
import com.lionotter.recipes.testutil.FakeImagePromptWriter
import com.lionotter.recipes.testutil.FakeRecipeImageScheduler
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

// Robolectric: building a SavedStateHandle from a typed route encodes it with android.net.Uri.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GenerateImageViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository = FakeRecipeRepository(
        Recipe(
            id = "r1",
            name = "Banana Bread",
            imagePrompt = "A loaf of banana bread",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        )
    )
    private val promptWriter = FakeImagePromptWriter()
    private val scheduler = FakeRecipeImageScheduler()
    private val settings = FakeAiSettings(openAiApiKey = "sk-openai")
    private lateinit var viewModel: GenerateImageViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = GenerateImageViewModel(
            SavedStateHandle(route = Route.EditRecipe("r1")),
            repository,
            WriteImagePromptUseCase(promptWriter, settings),
            scheduler,
            settings
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opens with the saved prompt and generates from the edited one`() = runTest(testDispatcher) {
        viewModel.open("A loaf of banana bread")
        assertEquals("A loaf of banana bread", viewModel.promptDraft.value)

        viewModel.onPromptChange("  Banana bread on a blue plate ")
        viewModel.generate()
        advanceUntilIdle()

        assertNull(viewModel.promptDraft.value)
        assertEquals(
            listOf(FakeRecipeImageScheduler.Scheduled("r1", "Banana bread on a blue plate", replaceImage = true)),
            scheduler.scheduled
        )
        assertEquals("Banana bread on a blue plate", repository.recipes.value.getValue("r1").imagePrompt)
        assertEquals(UserMessage(R.string.generating_image), viewModel.events.first())
    }

    @Test
    fun `the sparkle button fills in a prompt written by the AI`() = runTest(testDispatcher) {
        viewModel.open(null)
        assertEquals("", viewModel.promptDraft.value)

        viewModel.writePrompt()
        advanceUntilIdle()

        assertEquals("A written prompt", viewModel.promptDraft.value)
        assertEquals(1, promptWriter.requests.size)
    }

    @Test
    fun `a failed prompt is reported and the draft is kept`() = runTest(testDispatcher) {
        val error = RecipeError.Ai(AiFailure.OVERLOADED, 529)
        promptWriter.result = Outcome.Failure(error)
        viewModel.open("A loaf of banana bread")

        viewModel.writePrompt()
        advanceUntilIdle()

        assertEquals("A loaf of banana bread", viewModel.promptDraft.value)
        assertEquals(error.toUserMessage(), viewModel.events.first())
    }

    @Test
    fun `a prompt written after the dialog closed doesn't reopen it`() = runTest(testDispatcher) {
        viewModel.open("A loaf of banana bread")
        viewModel.writePrompt()
        viewModel.dismiss()
        advanceUntilIdle()

        assertNull(viewModel.promptDraft.value)
    }

    @Test
    fun `a failure to start is reported`() = runTest(testDispatcher) {
        scheduler.error = IllegalStateException("WorkManager isn't initialized")
        viewModel.open("A loaf of banana bread")

        viewModel.generate()
        advanceUntilIdle()

        assertEquals(UserMessage(R.string.generate_image_failed), viewModel.events.first())
    }
}
