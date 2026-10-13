package com.lionotter.recipes.ui.screens.editrecipe

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelKind
import com.lionotter.recipes.domain.model.ImageChange
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.testutil.FakeAppSettings
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeRecipeRepository
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.ui.screens.importbrowser.BrowsedPage
import com.lionotter.recipes.worker.PendingWork
import com.lionotter.recipes.worker.RecipeEditWork
import com.lionotter.recipes.worker.RecipeWorkScheduler
import com.lionotter.recipes.worker.WorkStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.IOException
import java.util.UUID
import kotlin.time.Instant

// Robolectric: building a SavedStateHandle from a typed route encodes it with android.net.Uri.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var recipeRepository: FakeRecipeRepository
    private val settings = FakeAppSettings().apply {
        editModelFlow.value = AiProviders.CLAUDE_OPUS
        thinkingFlow.value = false
    }
    private lateinit var imageStore: FakeImageStore
    private val contentUri = Uri.parse("content://media/picked/1")

    private val recipe = Recipe(
        id = "r1",
        name = "Pasta",
        sourceUrl = "https://example.com",
        imageUrl = "file:///old.jpg",
        tags = listOf("Dinner"),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    // Another recipe, so the user's tag vocabulary has more than this recipe's tags
    private val otherRecipe = recipe.copy(id = "r2", name = "Cake", tags = listOf("Dessert", "Quick", "dinner"))

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        recipeRepository = FakeRecipeRepository(recipe, otherRecipe)
        imageStore = FakeImageStore()
        imageStore.pickedImages[contentUri.toString()] = "file:///new.jpg"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        handle: SavedStateHandle = SavedStateHandle(route = Route.EditRecipe("r1")),
        scheduler: RecipeWorkScheduler = mockk(relaxed = true)
    ) = EditRecipeViewModel(
        handle,
        recipeRepository,
        settings,
        imageStore,
        scheduler,
        TestScope(UnconfinedTestDispatcher()),
        testDispatcher,
        testDispatcher
    )

    @Test
    fun `failure to start the AI edit shows an error instead of crashing`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit(any(), any(), any(), any(), any(), any(), any(), any()) } throws IOException("disk full")
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()

        val state = viewModel.editState.first { it is EditUiState.Error }
        // A localized message, not the exception's
        assertEquals(EditUiState.Error(UserMessage(R.string.error_save_changes)), state)
    }

    @Test
    fun `save as copy hands a copy of the picked image to the work and keeps its own`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every {
            scheduler.prepareEdit("r1", any(), any(), any(), true, any(), ImageChange.Replace("file:///new-copy.jpg"), any())
        } returns PendingWork(workId) {}
        every { scheduler.observeEdit(workId) } returns
            flowOf(WorkStatus.Finished(RecipeEditWork.Outcome.Success("r1", "Pasta", newRecipeId = "copy1")))
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.onImageSelected(contentUri)
        viewModel.imageUrl.first { it == "file:///new.jpg" }

        viewModel.saveAsCopy()

        assertEquals(EditRecipeEvent.Saved(newRecipeId = "copy1"), viewModel.events.first())
        assertEquals(emptyList<Any>(), recipeRepository.imageUrlUpdates)
        assertEquals("file:///new.jpg", viewModel.imageUrl.value)
        assertEquals(listOf("file:///new.jpg"), imageStore.copied)
        assertEquals(emptyList<String>(), imageStore.deletedLocal)
    }

    @Test
    fun `save as copy without the image passes the removal`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.removeImage()

        viewModel.saveAsCopy()
        advanceUntilIdle()

        verify { scheduler.prepareEdit("r1", any(), any(), any(), true, any(), ImageChange.Remove, any()) }
    }

    @Test
    fun `an unsaved picked image is dropped when an image is generated instead`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val recipeImage = viewModel.imageUrl.value
        viewModel.onImageSelected(contentUri)
        viewModel.imageUrl.first { it == "file:///new.jpg" }

        viewModel.discardPendingImageChange()
        advanceUntilIdle()

        assertEquals(recipeImage, viewModel.imageUrl.value)
        assertEquals(listOf("file:///new.jpg"), imageStore.deletedLocal)
    }

    @Test
    fun `the image copy is deleted if the copy work can't be started`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit(any(), any(), any(), any(), any(), any(), any(), any()) } throws IOException("disk full")
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.onImageSelected(contentUri)
        advanceUntilIdle()

        viewModel.saveAsCopy()
        advanceUntilIdle()

        assertEquals(listOf("file:///new-copy.jpg"), imageStore.deletedLocal)
        assertEquals(EditUiState.Error(UserMessage(R.string.error_save_changes)), viewModel.editState.value)
        assertEquals("file:///new.jpg", viewModel.imageUrl.value)
    }

    @Test
    fun `load state follows the recipe`() = runTest {
        val viewModel = createViewModel()
        assertEquals(RecipeLoadState.Loading, viewModel.loadState.value)
        advanceUntilIdle()
        assertEquals(RecipeLoadState.Loaded(recipe), viewModel.loadState.value)

        recipeRepository.recipes.value = emptyMap()
        advanceUntilIdle()
        assertEquals(RecipeLoadState.NotFound, viewModel.loadState.value)
    }

    @Test
    fun `changes are checked against the latest version of the recipe`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setTitle("Better Pasta")
        advanceUntilIdle()
        assertTrue(viewModel.hasUnsavedChanges.value)

        recipeRepository.updateTitleAndUrl("r1", "Better Pasta", recipe.sourceUrl)
        advanceUntilIdle()

        assertFalse(viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `untouched fields follow changes made elsewhere, so saving doesn't revert them`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.setSourceUrl("https://example.com/mine")

        recipeRepository.updateTitleAndUrl("r1", "Renamed Elsewhere", recipe.sourceUrl)
        advanceUntilIdle()

        assertEquals("Renamed Elsewhere", viewModel.title.value)
        assertEquals("https://example.com/mine", viewModel.sourceUrl.value)
        viewModel.saveEdits()
        advanceUntilIdle()
        assertEquals("Renamed Elsewhere", recipeRepository.recipes.value.getValue("r1").name)
        assertEquals("https://example.com/mine", recipeRepository.recipes.value.getValue("r1").sourceUrl)
        verify(exactly = 0) { scheduler.prepareEdit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tags start from the recipe and can be added and removed`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(listOf("Dinner"), viewModel.tags.value)
        assertFalse(viewModel.hasUnsavedChanges.value)

        // An existing tag is added in the spelling the user's recipes use
        viewModel.addTag(" dessert ")
        viewModel.addTag("DINNER")
        viewModel.setTagQuery("weeknight")
        viewModel.addTypedTag()
        advanceUntilIdle()

        assertEquals(listOf("Dinner", "Dessert", "weeknight"), viewModel.tags.value)
        assertEquals("", viewModel.tagQuery.value)
        assertTrue(viewModel.hasUnsavedChanges.value)

        viewModel.removeTag("weeknight")
        viewModel.removeTag("dessert")
        advanceUntilIdle()
        assertEquals(listOf("Dinner"), viewModel.tags.value)
        assertFalse(viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `the tag field finds existing tags the recipe doesn't have, or offers a new one`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setTagQuery("e")
        advanceUntilIdle()
        // "Dinner" is on the recipe already
        assertEquals(TagSearch(listOf("Dessert"), newTag = "e"), viewModel.tagSearch.value)

        viewModel.setTagQuery("quick ")
        advanceUntilIdle()
        assertEquals(TagSearch(listOf("Quick"), newTag = null), viewModel.tagSearch.value)

        viewModel.setTagQuery("dinner")
        advanceUntilIdle()
        assertEquals(TagSearch.Empty, viewModel.tagSearch.value)

        viewModel.setTagQuery(" ")
        advanceUntilIdle()
        assertEquals(TagSearch.Empty, viewModel.tagSearch.value)
    }

    @Test
    fun `existing tags that start with the query come first`() {
        assertEquals(
            listOf("Bread", "Breadsticks", "Shortbread"),
            EditRecipeViewModel.searchTags("bread", listOf("Shortbread", "Breadsticks", "Bread"), emptyList()).matches
        )
    }

    @Test
    fun `a tag-only change is saved directly without the AI`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.removeTag("Dinner")
        viewModel.addTag("Quick")
        viewModel.saveEdits()
        advanceUntilIdle()

        assertEquals(listOf("r1" to listOf("Quick")), recipeRepository.tagUpdates)
        assertEquals(EditRecipeEvent.Saved(newRecipeId = null), viewModel.events.first())
        verify(exactly = 0) { scheduler.prepareEdit(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertFalse(viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `a body change sends the tags in the Markdown and saves the edited tags directly`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        assertFalse(viewModel.markdownText.value.contains("**Tags:**"))

        viewModel.addTag("Quick")
        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()
        advanceUntilIdle()

        val markdown = "# Pasta\n\n*Source: [https://example.com](https://example.com)*\n\n" +
            "**Tags:** Dinner, Quick\n\nchanged"
        verify {
            scheduler.prepareEdit("r1", markdown, any(), any(), false, any(), any(), listOf("Dinner", "Quick"))
        }
        assertEquals(listOf("r1" to listOf("Dinner", "Quick")), recipeRepository.tagUpdates)
    }

    @Test
    fun `a body change without a tag change still keeps the recipe's tags over the AI's`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()
        advanceUntilIdle()

        verify { scheduler.prepareEdit("r1", match { "**Tags:** Dinner" in it }, any(), any(), false, any(), any(), listOf("Dinner")) }
        assertEquals(emptyList<Any>(), recipeRepository.tagUpdates)
    }

    @Test
    fun `save as copy gives the copy the edited tags and leaves the original's alone`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.removeTag("Dinner")
        viewModel.saveAsCopy()
        advanceUntilIdle()

        verify { scheduler.prepareEdit("r1", any(), any(), any(), true, any(), any(), emptyList()) }
        assertEquals(emptyList<Any>(), recipeRepository.tagUpdates)
    }

    @Test
    fun `edited tags and the tag field survive process death`() = runTest {
        val handle = SavedStateHandle(route = Route.EditRecipe("r1"))
        val first = createViewModel(handle)
        advanceUntilIdle()
        first.addTag("Dessert")
        first.setTagQuery("week")

        val restored = createViewModel(handle)
        advanceUntilIdle()

        assertEquals(listOf("Dinner", "Dessert"), restored.tags.value)
        assertEquals("week", restored.tagQuery.value)
        assertTrue(restored.hasUnsavedChanges.value)
    }

    @Test
    fun `untouched tags follow changes made elsewhere`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        recipeRepository.setTags("r1", listOf("Dinner", "Pasta"))
        advanceUntilIdle()

        assertEquals(listOf("Dinner", "Pasta"), viewModel.tags.value)
        assertFalse(viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `model and thinking follow the settings until chosen, and the choice survives recreation`() = runTest {
        val handle = SavedStateHandle(route = Route.EditRecipe("r1"))
        val viewModel = createViewModel(handle)
        advanceUntilIdle()
        assertEquals(AiProviders.CLAUDE_OPUS, viewModel.model.value?.id)

        settings.editModelFlow.value = AiProviders.CLAUDE_SONNET
        advanceUntilIdle()
        assertEquals(AiProviders.CLAUDE_SONNET, viewModel.model.value?.id)

        viewModel.setModel(AiProviders.CLAUDE_FABLE)
        viewModel.setThinkingEnabled(true)
        val recreated = createViewModel(handle)
        advanceUntilIdle()
        assertEquals(AiProviders.CLAUDE_FABLE, recreated.model.value?.id)
        assertTrue(recreated.thinkingEnabled.value)
    }

    @Test
    fun `a chosen model whose provider has no key falls back to the edit model`() = runTest {
        settings.openAiApiKeyFlow.value = "sk-proj-key"
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(
            AiProviders.availableModels(ModelKind.TEXT, mapOf("anthropic" to "k", "openai" to "k")),
            viewModel.availableModels.value
        )
        viewModel.setModel(AiProviders.GPT_ASTRA)
        advanceUntilIdle()
        assertEquals(AiProviders.GPT_ASTRA, viewModel.model.value?.id)

        settings.openAiApiKeyFlow.value = null
        advanceUntilIdle()
        assertEquals(AiProviders.CLAUDE_OPUS, viewModel.model.value?.id)
    }

    @Test
    fun `a failed in-place save keeps the picked image pending`() = runTest {
        imageStore.attachError = IOException("disk full")
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onImageSelected(contentUri)
        advanceUntilIdle()

        viewModel.saveEdits()
        advanceUntilIdle()

        assertEquals(EditUiState.Error(UserMessage(R.string.error_save_changes)), viewModel.editState.value)
        assertEquals("file:///new.jpg", viewModel.imageUrl.value)
        assertTrue(viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `edit that fails for a missing API key says so`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit(any(), any(), any(), any(), any(), any(), any(), any()) } returns PendingWork(workId) {}
        every { scheduler.observeEdit(workId) } returns
            flowOf(WorkStatus.Finished(RecipeEditWork.Outcome.Failed(RecipeError.NoApiKey, "r1")))
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()

        assertEquals(EditUiState.NoApiKey, viewModel.editState.first { it == EditUiState.NoApiKey })
    }

    @Test
    fun `a running edit is shown again after process death`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.observeEdit(workId) } returns flowOf(WorkStatus.Running(RecipeEditWork.Progress.Saving))
        val handle = SavedStateHandle(route = Route.EditRecipe("r1")).apply { set("edit_work_id", workId.toString()) }

        val viewModel = createViewModel(handle, scheduler)

        assertEquals(EditUiState.Loading(R.string.saving_recipe), viewModel.editState.first { it is EditUiState.Loading })
    }

    @Test
    fun `an edit that finished while the screen was gone is reported once`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.observeEdit(workId) } returns
            flowOf(WorkStatus.Finished(RecipeEditWork.Outcome.Success("r1", "Pasta", newRecipeId = null)))
        val handle = SavedStateHandle(route = Route.EditRecipe("r1")).apply { set("edit_work_id", workId.toString()) }

        val viewModel = createViewModel(handle, scheduler)
        advanceUntilIdle()

        assertEquals(EditRecipeEvent.Saved(newRecipeId = null), viewModel.events.first())
        assertNull(handle.get<String>("edit_work_id"))
    }

    @Test
    fun `a failed edit shows the error as a localized message`() = runTest {
        val workId = UUID.randomUUID()
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.observeEdit(workId) } returns flowOf(
            WorkStatus.Finished(RecipeEditWork.Outcome.Failed(RecipeError.NotARecipe("Just a shopping list"), "r1"))
        )
        val handle = SavedStateHandle(route = Route.EditRecipe("r1")).apply { set("edit_work_id", workId.toString()) }

        val viewModel = createViewModel(handle, scheduler)

        assertEquals(
            EditUiState.Error(UserMessage(R.string.error_not_a_recipe, "Just a shopping list")),
            viewModel.editState.first { it is EditUiState.Error }
        )
    }

    @Test
    fun `cancelling a running edit returns to editing`() = runTest {
        val workId = UUID.randomUUID()
        val status = MutableStateFlow<WorkStatus<RecipeEditWork.Progress, RecipeEditWork.Outcome>>(WorkStatus.Queued)
        val scheduler = mockk<RecipeWorkScheduler>()
        every { scheduler.prepareEdit(any(), any(), any(), any(), any(), any(), any(), any()) } returns PendingWork(workId) {}
        every { scheduler.observeEdit(workId) } returns status
        every { scheduler.cancel(workId) } answers { status.value = WorkStatus.Cancelled }
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()
        viewModel.setMarkdownText("changed")
        viewModel.saveEdits()
        viewModel.editState.first { it == EditUiState.Loading(R.string.edit_progress_preparing) }

        viewModel.cancelProcessing()

        assertEquals(EditUiState.Idle, viewModel.editState.first { it == EditUiState.Idle })
    }

    @Test
    fun `fields are populated from the recipe and start clean`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Pasta", viewModel.title.value)
        assertFalse(viewModel.hasUnsavedChanges.value)

        viewModel.setTitle("Better Pasta")
        advanceUntilIdle()
        assertTrue(viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `edited fields survive recreation from saved state`() = runTest {
        val handle = SavedStateHandle(route = Route.EditRecipe("r1"))
        val first = createViewModel(handle)
        advanceUntilIdle()
        first.setTitle("Edited")

        val second = createViewModel(handle)
        advanceUntilIdle()

        assertEquals("Edited", second.title.value)
        assertTrue(second.hasUnsavedChanges.value)
    }

    @Test
    fun `picked image is pending until save`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onImageSelected(contentUri)
        viewModel.imageUrl.first { it == "file:///new.jpg" }
        advanceUntilIdle()
        assertTrue(viewModel.hasUnsavedChanges.value)
        assertEquals(emptyList<Any>(), recipeRepository.imageUrlUpdates)

        viewModel.saveEdits()
        advanceUntilIdle()

        assertEquals(listOf(Triple("file:///new.jpg", "r1", "file:///old.jpg")), imageStore.attached)
        assertEquals(listOf("r1" to "gs://bucket/new.jpg"), recipeRepository.imageUrlUpdates)
        assertEquals(EditRecipeEvent.Saved(newRecipeId = null), viewModel.events.first())
    }

    @Test
    fun `removing image is applied on save`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.removeImage()
        advanceUntilIdle()
        assertEquals(null, viewModel.imageUrl.value)
        assertEquals(emptyList<Any>(), recipeRepository.imageUrlUpdates)

        viewModel.saveEdits()
        advanceUntilIdle()

        assertEquals(listOf("file:///old.jpg" to "r1"), imageStore.released)
        assertEquals(listOf("r1" to null), recipeRepository.imageUrlUpdates)
    }

    @Test
    fun `regenerating opens the source page in the browser first`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.requestRegenerate()

        assertEquals(EditRecipeEvent.OpenBrowser("https://example.com"), viewModel.events.first())
        verify(exactly = 0) { scheduler.prepareRegenerate(any(), any(), any(), any()) }
    }

    @Test
    fun `a recipe whose source isn't a web page regenerates without the browser`() = runTest {
        recipeRepository.updateTitleAndUrl("r1", "Pasta", "Grandma's notebook")
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.requestRegenerate()
        advanceUntilIdle()

        verify { scheduler.prepareRegenerate("r1", AiProviders.CLAUDE_OPUS, false, null) }
    }

    @Test
    fun `the page captured in the browser is handed to the regeneration`() = runTest {
        val scheduler = mockk<RecipeWorkScheduler>(relaxed = true)
        val viewModel = createViewModel(scheduler = scheduler)
        advanceUntilIdle()

        viewModel.regenerateFromOriginal(BrowsedPage("https://example.com/final", "/work-inputs/page.txt"))
        advanceUntilIdle()

        verify { scheduler.prepareRegenerate("r1", AiProviders.CLAUDE_OPUS, false, "/work-inputs/page.txt") }
    }
}
